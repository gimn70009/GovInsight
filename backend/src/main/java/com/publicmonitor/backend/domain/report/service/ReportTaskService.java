package com.publicmonitor.backend.domain.report.service;

import org.springframework.context.annotation.Lazy;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.client.dto.PythonReportJobRequest;
import com.publicmonitor.backend.domain.report.entity.*;
import com.publicmonitor.backend.domain.report.repository.ReportTaskRepository;
import java.time.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Lazy @Service @RequiredArgsConstructor
public class ReportTaskService {
    private final MonitoringRunRepository runs;
    private final ReportTaskRepository tasks;
    private final ObjectMapper mapper;
    private final Clock clock;
    public record Work(Long runId, String token, PythonReportJobRequest request, boolean delivery) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Work> claim(Long runId) {
        runs.findForUpdate(runId).orElseThrow();
        var task = tasks.lockByRunId(runId).orElse(null);
        var now = now();
        if (task == null || !task.due(now)) return Optional.empty();
        PythonReportJobRequest request = null;
        if (task.getState() == ReportTaskState.PENDING || task.getState() == ReportTaskState.RUNNING) {
            if (task.getAttemptCount() >= 3) completeMinimum(task, "보강 응답 대기/재시도 한도 초과");
            else {
                try {
                    request = mapper.readValue(task.getRequestJson(), PythonReportJobRequest.class);
                    if (request == null || request.documents() == null || request.documents().isEmpty())
                        throw new IllegalArgumentException("빈 보고서 보강 요청");
                }
                catch (RuntimeException e) { completeMinimum(task, "저장된 보강 요청을 읽지 못함"); }
            }
        }
        String token = task.claim(now());
        boolean delivery = task.getState() == ReportTaskState.DELIVERING;
        return Optional.of(new Work(runId, token, delivery ? null : request.withJobId(token), delivery));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestFailed(Work work) {
        runs.findForUpdate(work.runId()).orElseThrow();
        var task = tasks.lockByRunId(work.runId()).orElseThrow();
        if (!task.matches(work.token()) || task.getState() != ReportTaskState.RUNNING) return;
        if (task.getAttemptCount() >= 3) completeMinimum(task, "보강 요청 재시도 한도 초과");
        else task.retryGeneration(now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliveryFinished(Work work, boolean success) {
        runs.findForUpdate(work.runId()).orElseThrow();
        var task = tasks.lockByRunId(work.runId()).orElseThrow();
        if (!task.matches(work.token()) || task.getState() != ReportTaskState.DELIVERING) return;
        if (success) task.delivered(); else task.retryDelivery(now());
    }
    private void completeMinimum(ReportTask task, String reason) {
        var report = task.getReport();
        task.completeGeneration(report.getTitle(), report.getSummary(), now(), reason);
    }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
}
