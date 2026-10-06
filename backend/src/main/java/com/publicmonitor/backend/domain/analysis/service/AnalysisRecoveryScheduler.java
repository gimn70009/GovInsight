package com.publicmonitor.backend.domain.analysis.service;

import com.publicmonitor.backend.domain.analysis.repository.AnalysisTaskRepository;
import java.time.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j @Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "app.analysis.recovery.enabled", matchIfMissing = true)
public class AnalysisRecoveryScheduler {
    private final AnalysisTaskRepository tasks;
    private final AnalysisTaskService service;
    private final AnalysisTaskRunner runner;
    private final Clock clock;
    @Scheduled(fixedDelayString = "${app.analysis.recovery.interval-ms:10000}",
            initialDelayString = "${app.analysis.recovery.initial-delay-ms:10000}")
    public void recover() {
        for (Long runId : tasks.findUnqueued(PageRequest.of(0, 20))) {
            try { service.enqueue(runId); }
            catch (RuntimeException e) { log.warn("분석 복구 등록 실패. runId={}", runId); }
        }
        var now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        for (Long runId : tasks.findDue(now, PageRequest.of(0, 20))) {
            try { runner.run(runId); }
            catch (RuntimeException e) { log.warn("분석 복구 실패. runId={} type={}", runId, e.getClass().getSimpleName()); }
        }
    }
}
