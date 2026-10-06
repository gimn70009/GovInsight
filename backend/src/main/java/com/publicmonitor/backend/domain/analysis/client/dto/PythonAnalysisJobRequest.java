package com.publicmonitor.backend.domain.analysis.client.dto;

import java.util.List;

public record PythonAnalysisJobRequest(
        Long runId,
        List<PythonAnalysisDocumentRequest> documents,
        java.util.UUID jobId
) {
    public PythonAnalysisJobRequest(Long runId, List<PythonAnalysisDocumentRequest> documents) {
        this(runId, documents, null);
    }
    public PythonAnalysisJobRequest withJobId(String token) {
        return new PythonAnalysisJobRequest(runId, documents, java.util.UUID.fromString(token));
    }
}
