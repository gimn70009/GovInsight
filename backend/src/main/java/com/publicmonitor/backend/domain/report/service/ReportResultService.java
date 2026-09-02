package com.publicmonitor.backend.domain.report.service;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus;
import com.publicmonitor.backend.domain.report.event.ReportCompletedEvent;
import com.publicmonitor.backend.domain.report.exception.ReportException;
import com.publicmonitor.backend.domain.report.exception.ReportResponseCode;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import com.publicmonitor.backend.domain.report.web.dto.ReportResultRequest;
import com.publicmonitor.backend.domain.report.web.dto.ReportResultResponse;
import com.publicmonitor.backend.domain.report.web.dto.ReportResultStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Lazy
@Service
@RequiredArgsConstructor
public class ReportResultService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final MonitoringRunRepository runRepository;
    private final MonitoringReportRepository reportRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ReportResultResponse receive(ReportResultRequest request) {
        MonitoringRun run = runRepository.findById(request.runId())
                .orElseThrow(() -> new ReportException(ReportResponseCode.RUN_NOT_FOUND));
        MonitoringReport report = reportRepository.findByMonitoringRunId(request.runId())
                .orElseThrow(() -> new ReportException(ReportResponseCode.REPORT_NOT_FOUND));

        if (report.getStatus() == MonitoringReportStatus.COMPLETED) {
            return new ReportResultResponse(run.getId(), report.getId(), report.getStatus(), true);
        }
        if (run.getStatus() != MonitoringRunStatus.COLLECTED) {
            throw new ReportException(ReportResponseCode.INVALID_RUN_STATUS);
        }

        if (request.status() == ReportResultStatus.FAILED) {
            if (request.errorMessage() == null || request.errorMessage().isBlank()) {
                throw new ReportException(ReportResponseCode.INVALID_RESULT);
            }
            report.fail(request.errorMessage().strip());
            log.error(
                    "모니터링 보고서 생성 실패 결과 저장. runId={} jobId={} error={}",
                    request.runId(), request.jobId(), request.errorMessage().strip()
            );
            return new ReportResultResponse(run.getId(), report.getId(), report.getStatus(), false);
        }

        if (request.title() == null || request.title().isBlank()
                || request.summary() == null || request.summary().isBlank()) {
            throw new ReportException(ReportResponseCode.INVALID_RESULT);
        }
        if (report.getStatus() != MonitoringReportStatus.PENDING) {
            throw new ReportException(ReportResponseCode.INVALID_REPORT_STATUS);
        }

        LocalDateTime completedAt = LocalDateTime.now(clock.withZone(SERVICE_ZONE));
        report.complete(request.title().strip(), request.summary().strip(), completedAt);
        run.completeReport(completedAt);
        eventPublisher.publishEvent(new ReportCompletedEvent(run.getId()));
        log.info("모니터링 보고서 저장 완료. runId={} jobId={} reportId={}",
                request.runId(), request.jobId(), report.getId());
        return new ReportResultResponse(run.getId(), report.getId(), report.getStatus(), false);
    }
}
