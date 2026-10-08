package com.publicmonitor.backend.domain.monitoring.web.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

public record CancelPendingMonitoringScheduleRequest(
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime scheduledAt
) {
}
