package com.publicmonitor.backend.domain.report.client.dto;

import com.publicmonitor.backend.domain.report.client.PythonReportJobStatus;
import java.util.UUID;

public record PythonReportJobResponse(
        UUID jobId,
        PythonReportJobStatus status,
        int documentCount
) {
}
