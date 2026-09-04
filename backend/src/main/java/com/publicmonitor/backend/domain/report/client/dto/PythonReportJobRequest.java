package com.publicmonitor.backend.domain.report.client.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PythonReportJobRequest(
        Long runId,
        LocalDateTime requestedAt,
        int totalSourceCount,
        int detectedDocumentCount,
        int warningCount,
        List<PythonReportDocumentRequest> documents
) {
}