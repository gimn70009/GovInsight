package com.publicmonitor.backend.domain.monitoring.web.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSchedule;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringScheduleFrequency;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

public record MonitoringScheduleResponse(
        boolean enabled,
        MonitoringScheduleFrequency frequency,
        LocalTime executionTime,
        Set<DayOfWeek> customDays
) {
    public static MonitoringScheduleResponse from(MonitoringSchedule schedule) {
        return new MonitoringScheduleResponse(
                schedule.isEnabled(),
                schedule.getFrequency(),
                schedule.getExecutionTime(),
                schedule.getSelectedDays()
        );
    }
}
