package com.publicmonitor.backend.domain.monitoring.entity;

import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "monitoring_schedules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
        name = "monitoring_schedules_sequence_generator",
        sequenceName = "monitoring_schedules_sequence",
        allocationSize = 1
)
public class MonitoringSchedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "monitoring_schedules_sequence_generator")
    @Column(name = "schedule_id")
    private Long id;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private MonitoringScheduleFrequency frequency = MonitoringScheduleFrequency.DAILY;

    @Column(name = "execution_time", nullable = false)
    private LocalTime executionTime = LocalTime.of(9, 0);

    @Column(name = "custom_days", length = 80)
    private String customDays;

    @Column(name = "last_attempted_date")
    private LocalDate lastAttemptedDate;

    @Column(name = "pending_scheduled_at")
    private LocalDateTime pendingScheduledAt;

    public static MonitoringSchedule defaultSchedule() {
        return new MonitoringSchedule();
    }

    public void update(
            boolean enabled,
            MonitoringScheduleFrequency frequency,
            LocalTime executionTime,
            Set<DayOfWeek> customDays
    ) {
        LocalTime normalizedTime = executionTime.withSecond(0).withNano(0);
        String normalizedCustomDays = frequency == MonitoringScheduleFrequency.CUSTOM
                ? customDays.stream().sorted().map(Enum::name).collect(Collectors.joining(","))
                : null;
        boolean scheduleChanged = this.enabled != enabled
                || this.frequency != frequency
                || !this.executionTime.equals(normalizedTime)
                || !java.util.Objects.equals(this.customDays, normalizedCustomDays);

        this.enabled = enabled;
        this.frequency = frequency;
        this.executionTime = normalizedTime;
        this.customDays = normalizedCustomDays;
        if (scheduleChanged) {
            this.lastAttemptedDate = null;
            this.pendingScheduledAt = null;
        }
    }

    public Set<DayOfWeek> getSelectedDays() {
        if (frequency == MonitoringScheduleFrequency.DAILY) {
            return EnumSet.allOf(DayOfWeek.class);
        }
        if (frequency == MonitoringScheduleFrequency.WEEKDAYS) {
            return EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        }
        if (customDays == null || customDays.isBlank()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        return EnumSet.copyOf(
                java.util.Arrays.stream(customDays.split(","))
                        .map(DayOfWeek::valueOf)
                        .collect(Collectors.toSet())
        );
    }

    public Optional<LocalDate> dueDate(LocalDateTime now, Duration catchUpWindow) {
        if (!enabled) {
            return Optional.empty();
        }
        LocalDateTime scheduledAt = now.toLocalDate().atTime(executionTime);
        if (scheduledAt.isAfter(now)) {
            scheduledAt = scheduledAt.minusDays(1);
        }
        LocalDate scheduledDate = scheduledAt.toLocalDate();
        if (!getSelectedDays().contains(scheduledDate.getDayOfWeek())
                || (lastAttemptedDate != null && !scheduledDate.isAfter(lastAttemptedDate))
                || now.isAfter(scheduledAt.plus(catchUpWindow))) {
            return Optional.empty();
        }
        return Optional.of(scheduledDate);
    }

    public boolean queueDue(LocalDateTime now, Duration catchUpWindow) {
        var due = dueDate(now, catchUpWindow);
        if (due.isEmpty()) {
            return false;
        }
        var scheduledDate = due.get();
        // Consume every occurrence, even when it is merged into the existing slot.
        markAttempted(scheduledDate);
        if (pendingScheduledAt == null) {
            pendingScheduledAt = scheduledDate.atTime(executionTime);
        }
        return true;
    }

    public boolean cancelPending(LocalDateTime expected) {
        if (expected == null || !expected.equals(pendingScheduledAt)) {
            return false;
        }
        clearPending();
        return true;
    }

    public void clearPending() {
        pendingScheduledAt = null;
    }

    public void markAttempted(LocalDate date) {
        this.lastAttemptedDate = date;
    }
}
