package com.publicmonitor.backend.domain.report.client.dto;

import java.util.List;

public record PythonReportJobRequest(
        Long runId,
        int totalSourceCount,
        int detectedDocumentCount,
        int warningCount,
        List<PythonReportDocumentRequest> documents
) {
}
