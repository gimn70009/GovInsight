package com.publicmonitor.backend.domain.analysis.client.dto;
import com.publicmonitor.backend.domain.settings.web.dto.EvaluationSettingsSnapshot;
import java.util.List;
import java.util.UUID;
public record PythonAnalysisJobRequest(Long runId, List<PythonAnalysisDocumentRequest> documents,
        UUID jobId, EvaluationSettingsSnapshot evaluationSettings) {
    public PythonAnalysisJobRequest(Long runId, List<PythonAnalysisDocumentRequest> documents) {
        this(runId, documents, null, null);
    }
    public PythonAnalysisJobRequest(Long runId, List<PythonAnalysisDocumentRequest> documents, UUID jobId) {
        this(runId, documents, jobId, null);
    }
    public PythonAnalysisJobRequest withJobId(String token) {
        return new PythonAnalysisJobRequest(runId, documents, UUID.fromString(token), evaluationSettings);
    }
}
