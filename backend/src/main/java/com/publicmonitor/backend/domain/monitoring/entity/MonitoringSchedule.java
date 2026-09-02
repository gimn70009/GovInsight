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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
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

    public boolean isDue(LocalDate date, LocalTime time) {
        return enabled
                && getSelectedDays().contains(date.getDayOfWeek())
                && executionTime.getHour() == time.getHour()
                && executionTime.getMinute() == time.getMinute()
                && !date.equals(lastAttemptedDate);
    }

    public void markAttempted(LocalDate date) {
        this.lastAttemptedDate = date;
    }
}
