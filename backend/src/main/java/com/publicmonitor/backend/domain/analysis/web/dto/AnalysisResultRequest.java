package com.publicmonitor.backend.domain.analysis.web.dto;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisFavorability;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "Python AI 문서 분석 결과 전달 요청")
public record AnalysisResultRequest(
        @NotNull @Positive Long runId,
        @NotNull UUID jobId,
        @NotNull List<@Valid AnalysisResult> results,
        @NotNull List<@Valid AnalysisFailure> failures
) {

    @AssertTrue(message = "분석 성공 또는 실패 결과가 한 건 이상 필요합니다.")
    public boolean hasResult() {
        return results != null && failures != null && (!results.isEmpty() || !failures.isEmpty());
    }

    public record AnalysisResult(
            @NotNull @Positive Long detectionId,
            @NotNull @Positive Long documentId,
            @NotNull @Positive Long versionId,
            @NotBlank @Size(max = 4000) String summary,
            @NotNull @Size(min = 1, max = 8) List<@NotBlank @Size(max = 1000) String> keyPoints,
            @NotNull DocumentImportance importance,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull AnalysisEligibility eligibility,
            @NotNull AnalysisFavorability favorableOrNot,
            @NotNull @Valid Proposal proposal,
            @NotNull @Valid Opportunity opportunity,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 100) String> usedTools,
            @NotBlank @Size(max = 100) String modelName
    ) {
    }

    public record Proposal(
            @NotNull @Size(min = 1, max = 6) List<@Valid Section> sections
    ) {
    }

    public record Section(
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 1000) String body
    ) {
    }

    public record Opportunity(
            @NotNull @Size(min = 5, max = 5) List<@Valid OpportunityDimension> dimensions
    ) {
        @AssertTrue(message = "기회 점수의 다섯 평가 항목이 각각 한 번씩 필요합니다.")
        public boolean hasAllDimensions() {
            return dimensions == null || dimensions.stream()
                    .map(OpportunityDimension::type)
                    .distinct()
                    .count() == OpportunityDimensionType.values().length;
        }
    }

    public record OpportunityDimension(
            @NotNull OpportunityDimensionType type,
            @NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer score,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record AnalysisFailure(
            @NotNull @Positive Long detectionId,
            @NotNull @Positive Long documentId,
            @NotNull @Positive Long versionId,
            @NotBlank @Size(max = 2000) String errorMessage
    ) {
    }
}
