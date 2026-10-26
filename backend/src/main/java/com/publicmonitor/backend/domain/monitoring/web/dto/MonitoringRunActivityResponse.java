package com.publicmonitor.backend.domain.monitoring.web.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "전체 실행 이력 기준 진행 중인 모니터링 상태")
public record MonitoringRunActivityResponse(
        @Schema(description = "수집·분석·보고서 생성이 진행 중이면 true") boolean running,
        @Schema(description = "진행 중인 실행 ID, 없으면 null") Long runId,
        @Schema(description = "진행 상태, 없으면 null") MonitoringRunStatus status
) {}
