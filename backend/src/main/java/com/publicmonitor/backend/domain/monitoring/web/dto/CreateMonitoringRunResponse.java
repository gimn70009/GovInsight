package com.publicmonitor.backend.domain.monitoring.web.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "모니터링 실행 생성 결과")
public record CreateMonitoringRunResponse(
        @Schema(description = "실행 식별자", example = "1") Long runId,
        @Schema(description = "현재 실행 상태", example = "ACCEPTED") MonitoringRunStatus status,
        @Schema(description = "실행 시작 방식", example = "MANUAL") MonitoringTriggerType triggerType,
        @Schema(description = "실행 대상 소스 수", example = "5") int totalSourceCount,
        @Schema(description = "실행 요청 시각", example = "2026-08-20T09:00:00") LocalDateTime requestedAt
) {

    public static CreateMonitoringRunResponse from(MonitoringRun run) {
        return new CreateMonitoringRunResponse(
                run.getId(),
                run.getStatus(),
                run.getTriggerType(),
                run.getTotalSourceCount(),
                run.getRequestedAt()
        );
    }
}
