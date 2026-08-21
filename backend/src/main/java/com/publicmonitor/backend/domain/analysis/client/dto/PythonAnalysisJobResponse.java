package com.publicmonitor.backend.domain.analysis.client.dto;

import com.publicmonitor.backend.domain.analysis.client.PythonAnalysisJobStatus;
import java.util.UUID;

public record PythonAnalysisJobResponse(
        UUID jobId,
        PythonAnalysisJobStatus status,
        int documentCount
) {
}
