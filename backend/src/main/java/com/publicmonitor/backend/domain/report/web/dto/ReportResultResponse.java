package com.publicmonitor.backend.domain.report.web.dto;

import com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus;

public record ReportResultResponse(
        Long runId,
        Long reportId,
        MonitoringReportStatus status,
        boolean duplicate
) {
}
