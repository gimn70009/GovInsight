package com.publicmonitor.backend.domain.analysis.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI 문서 분석 결과 저장 응답")
public record AnalysisResultResponse(
        Long runId,
        int storedAnalysisCount,
        int duplicateAnalysisCount,
        int failedAnalysisCount
) {
}
