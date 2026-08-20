package com.publicmonitor.backend.domain.monitoring.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "모니터링 소스 활성 상태 변경 요청")
public record UpdateMonitoringSourceEnabledRequest(
        @Schema(description = "변경할 활성 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "활성 여부는 필수입니다.")
        Boolean enabled
) {
}
