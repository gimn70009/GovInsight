package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSchedule;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringScheduleRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.monitoring.schedule.enabled", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class MonitoringScheduleRunner {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final EnumSet<MonitoringRunStatus> ACTIVE_STATUSES = EnumSet.of(
            MonitoringRunStatus.REQUESTED,
            MonitoringRunStatus.ACCEPTED,
            MonitoringRunStatus.RUNNING,
            MonitoringRunStatus.COLLECTED
    );

    private final MonitoringScheduleRepository scheduleRepository;
    private final MonitoringRunRepository runRepository;
    private final MonitoringRunService runService;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void runIfDue() {
        LocalDateTime now = LocalDateTime.now(clock.withZone(SERVICE_ZONE));
        MonitoringSchedule schedule = scheduleRepository.findAll().stream().findFirst().orElse(null);
        if (schedule == null || !schedule.isDue(now.toLocalDate(), now.toLocalTime())) {
            return;
        }

        LocalDate today = now.toLocalDate();
        schedule.markAttempted(today);
        scheduleRepository.save(schedule);
        if (runRepository.existsByStatusIn(ACTIVE_STATUSES)) {
            log.info("진행 중인 모니터링이 있어 자동 실행을 건너뜁니다. date={}", today);
            return;
        }

        try {
            var response = runService.create(MonitoringTriggerType.SCHEDULED);
            log.info("자동 모니터링을 시작했습니다. runId={} date={}", response.runId(), today);
        } catch (RuntimeException exception) {
            log.error("자동 모니터링 시작에 실패했습니다. date={}", today, exception);
        }
    }
}
