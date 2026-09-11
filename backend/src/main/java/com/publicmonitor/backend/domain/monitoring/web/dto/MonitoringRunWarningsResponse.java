package com.publicmonitor.backend.domain.monitoring.web.dto;

import java.util.List;

public record MonitoringRunWarningsResponse(Long runId, int warningCount, List<Warning> warnings) {
    public record Warning(String stage, String organizationName, String boardName,
            String documentTitle, String fileName, String message, int count) {}
}
