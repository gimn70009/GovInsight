package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MonitoringCollectionRecovery {
    private static final EnumSet<MonitoringRunStatus> COLLECTION_STATUSES = EnumSet.of(
            MonitoringRunStatus.REQUESTED, MonitoringRunStatus.ACCEPTED, MonitoringRunStatus.RUNNING);
    private final MonitoringRunRepository runs;
    private final MonitoringCollectionRecoveryService service;
    private final Clock clock;
    private final Duration timeout;
    private final boolean enabled;

    public MonitoringCollectionRecovery(
            MonitoringRunRepository runs, MonitoringCollectionRecoveryService service, Clock clock,
            @Value("${app.monitoring.collection-recovery.timeout:PT1H}") Duration timeout,
            @Value("${app.monitoring.collection-recovery.enabled:true}") boolean enabled) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("수집 결과 대기 시간은 0보다 커야 합니다.");
        }
        this.runs = runs;
        this.service = service;
        this.clock = clock;
        this.timeout = timeout;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.monitoring.collection-recovery.interval-ms:60000}",
            initialDelayString = "${app.monitoring.collection-recovery.initial-delay-ms:10000}")
    public void recover() {
        if (!enabled) return;
        var now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var cutoff = now.minus(timeout);
        for (Long runId : runs.findExpiredCollections(COLLECTION_STATUSES, cutoff, PageRequest.of(0, 100))) {
            try {
                if (service.expire(runId, cutoff, now)) {
                    log.warn("수집 결과 대기 시간이 만료되어 실행을 실패 처리했습니다. runId={}", runId);
                }
            } catch (RuntimeException exception) {
                log.warn("수집 실행 만료 처리 실패. runId={} type={}", runId, exception.getClass().getSimpleName());
            }
        }
    }
}
