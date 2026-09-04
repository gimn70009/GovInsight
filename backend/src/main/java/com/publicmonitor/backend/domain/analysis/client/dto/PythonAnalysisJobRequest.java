package com.publicmonitor.backend.domain.analysis.client.dto;

import java.util.List;

public record PythonAnalysisJobRequest(
        Long runId,
        List<PythonAnalysisDocumentRequest> documents
) {
}
