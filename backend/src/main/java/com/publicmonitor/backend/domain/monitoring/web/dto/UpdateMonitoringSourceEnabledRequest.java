package com.publicmonitor.backend.domain.monitoring.web.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateMonitoringSourceEnabledRequest(
        @NotNull(message = "활성 여부는 필수입니다.")
        Boolean enabled
) {
}
