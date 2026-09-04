package com.publicmonitor.backend.domain.monitoring.web.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringScheduleFrequency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

public record UpdateMonitoringScheduleRequest(
        boolean enabled,
        @NotNull MonitoringScheduleFrequency frequency,
        @NotNull LocalTime executionTime,
        @NotNull Set<DayOfWeek> customDays
) {
    @AssertTrue(message = "직접 선택 일정에는 하나 이상의 요일이 필요합니다.")
    public boolean isCustomDaysValid() {
        return frequency != MonitoringScheduleFrequency.CUSTOM || !customDays.isEmpty();
    }
}
