package com.publicmonitor.backend.domain.monitoring.web.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import java.time.LocalDateTime;

public record CreateMonitoringRunResponse(
        Long runId,
        MonitoringRunStatus status,
        MonitoringTriggerType triggerType,
        int totalSourceCount,
        LocalDateTime requestedAt
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
