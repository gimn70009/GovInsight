package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSchedule;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringJobAcceptanceException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringScheduleRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringSourceRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringRunResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.monitoring.schedule.enabled", matchIfMissing = true)
public class MonitoringScheduleQueueService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private final MonitoringScheduleRepository schedules;
    private final MonitoringSourceRepository sources;
    private final MonitoringRunRepository runs;
    private final MonitoringRunService runService;
    private final Clock clock;
    private final Duration catchUpWindow;

    public MonitoringScheduleQueueService(
            MonitoringScheduleRepository schedules, MonitoringSourceRepository sources,
            MonitoringRunRepository runs, MonitoringRunService runService, Clock clock,
            @Value("${app.monitoring.schedule.catch-up-window:PT5M}") Duration catchUpWindow
    ) {
        if (catchUpWindow.isZero() || catchUpWindow.isNegative()
                || catchUpWindow.compareTo(Duration.ofDays(1)) >= 0) {
            throw new IllegalArgumentException("예약 지연 허용 시간은 0보다 크고 24시간보다 작아야 합니다.");
        }
        this.schedules = schedules;
        this.sources = sources;
        this.runs = runs;
        this.runService = runService;
        this.clock = clock;
        this.catchUpWindow = catchUpWindow;
    }

    @Transactional(readOnly = true)
    public boolean hasWork() {
        return schedules.findAll().stream()
                .min(Comparator.comparing(MonitoringSchedule::getId))
                .filter(MonitoringSchedule::isEnabled)
                .map(schedule -> schedule.getPendingScheduledAt() != null
                        || schedule.dueDate(now(), catchUpWindow).isPresent())
                .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueDue() {
        schedules.findAllForUpdate().stream().findFirst()
                .ifPresent(schedule -> schedule.queueDue(now(), catchUpWindow));
    }

    @Transactional(noRollbackFor = MonitoringJobAcceptanceException.class)
    public Optional<CreateMonitoringRunResponse> startPending() {
        // Manual requests use the same order. Do not consume the durable slot
        // until both the settings and run exclusivity have been checked.
        var lockedSources = sources.findAllForRunCreation();
        var schedule = schedules.findAllForUpdate().stream().findFirst().orElse(null);
        if (schedule == null || !schedule.isEnabled() || schedule.getPendingScheduledAt() == null
                || lockedSources.stream().noneMatch(MonitoringSource::isEnabled)
                || runs.existsByStatusIn(MonitoringRunStatus.inProgress())) {
            return Optional.empty();
        }
        schedule.clearPending();
        // REQUIRED joins this transaction: clearing the slot and creating the run
        // commit together, including a recorded Python acceptance failure.
        return Optional.of(runService.create(MonitoringTriggerType.SCHEDULED));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(SERVICE_ZONE));
    }
}
