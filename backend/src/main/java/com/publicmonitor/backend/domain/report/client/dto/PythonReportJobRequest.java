package com.publicmonitor.backend.domain.report.client.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PythonReportJobRequest(
        Long runId,
        LocalDateTime requestedAt,
        int totalSourceCount,
        int detectedDocumentCount,
        int warningCount,
        List<PythonReportDocumentRequest> documents,
        java.util.UUID jobId
) {
    public PythonReportJobRequest(Long runId, LocalDateTime requestedAt, int totalSourceCount,
            int detectedDocumentCount, int warningCount, List<PythonReportDocumentRequest> documents) {
        this(runId, requestedAt, totalSourceCount, detectedDocumentCount, warningCount, documents, null);
    }
    public PythonReportJobRequest withJobId(String value) {
        return new PythonReportJobRequest(runId, requestedAt, totalSourceCount, detectedDocumentCount, warningCount, documents, java.util.UUID.fromString(value));
    }
}