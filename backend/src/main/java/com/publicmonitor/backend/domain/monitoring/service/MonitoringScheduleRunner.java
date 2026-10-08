package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringAlreadyRunningException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.monitoring.schedule.enabled", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class MonitoringScheduleRunner {

    private final MonitoringScheduleQueueService queue;
    private final MonitoringRunRepository runRepository;
    private final MonitoringCollectionRecovery collectionRecovery;

    @Scheduled(cron = "*/5 * * * * *", zone = "Asia/Seoul", scheduler = "monitoringScheduleScheduler")
    public void runIfDue() {
        if (!queue.hasWork()) {
            return;
        }
        // Persist the occurrence before recovery or dispatch can fail or block.
        queue.enqueueDue();
        collectionRecovery.recover();
        if (runRepository.existsByStatusIn(MonitoringRunStatus.inProgress())) {
            return;
        }
        try {
            queue.startPending().ifPresent(response ->
                    log.info("대기 중인 자동 모니터링을 시작했습니다. runId={}", response.runId()));
        } catch (MonitoringAlreadyRunningException exception) {
            log.info("다른 실행이 시작되어 자동 모니터링 대기를 유지합니다.");
        } catch (RuntimeException exception) {
            log.error("대기 중인 자동 모니터링 시작에 실패했습니다.", exception);
        }
    }
}
