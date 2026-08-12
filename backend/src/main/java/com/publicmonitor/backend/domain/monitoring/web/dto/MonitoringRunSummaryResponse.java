package com.publicmonitor.backend.domain.monitoring.web.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import java.time.LocalDateTime;

public record MonitoringRunSummaryResponse(
        Long runId,
        LocalDateTime requestedAt,
        MonitoringRunStatus status,
        int totalSourceCount,
        int detectedDocumentCount,
        int warningCount
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
