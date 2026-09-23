package com.publicmonitor.backend.domain.report.service;

import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import com.publicmonitor.backend.domain.report.repository.ReportTaskRepository;
import java.time.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j @Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "app.report.recovery.enabled", matchIfMissing = true)
public class ReportRecoveryScheduler {
    private final ReportTaskRepository tasks;
    private final ReportTaskRunner runner;
    private final MonitoringReportRepository reports;
    private final ReportPreparationService preparation;
    private final Clock clock;
    @Scheduled(fixedDelayString = "${app.report.recovery.interval-ms:5000}", initialDelayString = "${app.report.recovery.initial-delay-ms:10000}")
    public void recover() {
        for (Long runId : reports.findUnqueued(PageRequest.of(0, 20))) {
            try { preparation.prepare(runId); }
            catch (RuntimeException e) { log.warn("기존 보고서 복구 준비 실패. runId={}", runId); }
        }
        var now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        for (Long runId : tasks.findDue(now, PageRequest.of(0, 20))) {
            try { runner.run(runId); }
            catch (RuntimeException e) { log.warn("보고서 작업 복구 실패. runId={} type={}", runId, e.getClass().getSimpleName()); }
        }
    }
}
