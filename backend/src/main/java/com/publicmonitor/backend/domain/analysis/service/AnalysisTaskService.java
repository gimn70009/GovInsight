package com.publicmonitor.backend.domain.analysis.service;

import com.publicmonitor.backend.domain.analysis.client.dto.PythonAnalysisJobRequest;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisTask;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisTaskRepository;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.event.ProposalCompletedEvent;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import java.time.*;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
public class AnalysisTaskService {
    private final MonitoringRunRepository runs;
    private final AnalysisTaskRepository tasks;
    private final AnalysisJobRequestService requests;
    private final MonitoringReportRepository reports;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final Duration minimumLease;
    private final Duration perDocumentLease;

    public AnalysisTaskService(MonitoringRunRepository runs, AnalysisTaskRepository tasks,
            AnalysisJobRequestService requests, MonitoringReportRepository reports, ObjectMapper mapper,
            Clock clock, ApplicationEventPublisher events,
            @Value("${app.analysis.recovery.minimum-lease:PT30M}") Duration minimumLease,
            @Value("${app.analysis.recovery.per-document-lease:PT15M}") Duration perDocumentLease) {
        if (minimumLease.isNegative() || minimumLease.isZero() || perDocumentLease.isNegative() || perDocumentLease.isZero())
            throw new IllegalArgumentException("분석 복구 대기 시간은 양수여야 합니다.");
        this.runs = runs; this.tasks = tasks; this.requests = requests; this.reports = reports;
        this.mapper = mapper; this.clock = clock; this.events = events;
        this.minimumLease = minimumLease; this.perDocumentLease = perDocumentLease;
    }
    public record Work(Long runId, String token, PythonAnalysisJobRequest request) {}

    @Transactional
    public void enqueue(Long runId) {
        var run = runs.findForUpdate(runId).orElseThrow();
        if (run.getStatus() == MonitoringRunStatus.COLLECTED && tasks.findByRunId(runId).isEmpty())
            tasks.save(AnalysisTask.pending(run, now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Work> claim(Long runId) {
        var run = runs.findForUpdate(runId).orElseThrow();
        var task = tasks.findByRunId(runId).orElse(null);
        if (task == null || !task.due(now())) return Optional.empty();
        if (run.getStatus() != MonitoringRunStatus.COLLECTED) {
            task.complete();
            return Optional.empty();
        }
        if (reports.findByMonitoringRunId(runId).isPresent()) {
            task.complete();
            events.publishEvent(new ProposalCompletedEvent(runId));
            return Optional.empty();
        }
        if (task.getAttemptCount() >= 3) {
            task.fail(now());
            return Optional.empty();
        }
        String token = task.claim(now(), minimumLease);
        PythonAnalysisJobRequest request;
        try {
            request = task.getRequestJson() == null ? requests.prepareForRecovery(runId).orElse(null)
                    : mapper.readValue(task.getRequestJson(), PythonAnalysisJobRequest.class);
            if (request != null) {
                if (request.documents() == null || request.documents().isEmpty() || !runId.equals(request.runId()))
                    throw new IllegalArgumentException("저장된 분석 요청이 올바르지 않습니다.");
                task.saveRequest(mapper.writeValueAsString(request), now(), lease(request));
            }
        } catch (RuntimeException exception) {
            retryOrFail(task);
            return Optional.empty();
        }
        if (request == null) {
            task.complete();
            events.publishEvent(new ProposalCompletedEvent(runId));
            return Optional.empty();
        }
        return Optional.of(new Work(runId, token, request.withJobId(token)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestFailed(Work work) {
        runs.findForUpdate(work.runId()).orElseThrow();
        var task = tasks.findByRunId(work.runId()).orElseThrow();
        if (work.token().equals(task.getAttemptId()) && task.getState() == AnalysisTask.State.RUNNING)
            retryOrFail(task);
    }

    public Duration lease(PythonAnalysisJobRequest request) {
        var scaled = perDocumentLease.multipliedBy(request.documents().size());
        return scaled.compareTo(minimumLease) > 0 ? scaled : minimumLease;
    }
    public Duration proposalLease(AnalysisTask task) {
        return lease(mapper.readValue(task.getRequestJson(), PythonAnalysisJobRequest.class));
    }
    private void retryOrFail(AnalysisTask task) {
        if (task.getAttemptCount() >= 3) task.fail(now()); else task.retry(now());
    }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
}
