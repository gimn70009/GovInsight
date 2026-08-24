package com.publicmonitor.backend.domain.analysis.client.dto;

public record PythonPreviousAnalysisRequest(
        String summary,
        String keyPoints,
        String eligibility,
        String favorableOrNot,
        String proposalDirection
) {
}
