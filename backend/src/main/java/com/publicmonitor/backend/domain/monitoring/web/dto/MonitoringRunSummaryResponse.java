package com.publicmonitor.backend.domain.monitoring.web.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "모니터링 실행 이력 요약")
public record MonitoringRunSummaryResponse(
        @Schema(description = "실행 식별자", example = "1") Long runId,
        @Schema(description = "실행 요청 시각", example = "2026-08-20T09:00:00") LocalDateTime requestedAt,
        @Schema(description = "전체 실행 상태", example = "COLLECTED") MonitoringRunStatus status,
        @Schema(description = "실행 대상 소스 수", example = "5") int totalSourceCount,
        @Schema(description = "이번 실행에서 감지한 문서 수", example = "10") int detectedDocumentCount,
        @Schema(description = "실행 중 발생한 경고 수", example = "1") int warningCount
) {

    public static MonitoringRunSummaryResponse from(MonitoringRun run) {
        return new MonitoringRunSummaryResponse(
                run.getId(),
                run.getRequestedAt(),
                run.getStatus(),
                run.getTotalSourceCount(),
                run.getDetectedDocumentCount(),
                run.getWarningCount()
        );
    }
}
