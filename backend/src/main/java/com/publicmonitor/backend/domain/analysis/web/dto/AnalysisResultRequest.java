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
            @NotBlank @Size(max = 100) String modelName,
            @Valid ComparisonSummary comparisonSummary,
            @Size(max = 8000) String similarityProfile,
            @Size(max = 3072) List<@NotNull Double> similarityEmbedding,
            @Size(max = 100) String embeddingModelName
    ) {
        public AnalysisResult(
                Long detectionId,
                Long documentId,
                Long versionId,
                String summary,
                List<String> keyPoints,
                DocumentImportance importance,
                String reason,
                AnalysisEligibility eligibility,
                AnalysisFavorability favorableOrNot,
                Proposal proposal,
                Opportunity opportunity,
                List<String> usedTools,
                String modelName
        ) {
            this(detectionId, documentId, versionId, summary, keyPoints, importance, reason,
                    eligibility, favorableOrNot, proposal, opportunity, usedTools, modelName,
                    null, null, null, null);
        }
    }

    public record ComparisonSummary(
            @NotBlank @Size(max = 1500) String purpose,
            @NotBlank @Size(max = 500) String supportScale,
            @NotBlank @Size(max = 300) String applicationDeadline,
            @NotBlank @Size(max = 1000) String eligibility,
            @NotBlank @Size(max = 1000) String requiredPartner,
            @NotNull @Size(min = 5, max = 5) List<@Valid LegalRiskFinding> legalRisks
    ) {
    }

    public record LegalRiskFinding(
            @NotBlank @Size(max = 40) String type,
            @NotBlank @Size(max = 40) String status,
            @NotBlank @Size(max = 500) String summary,
            @Size(max = 300) String evidenceExcerpt
    ) {
    }
    public record Proposal(
            @NotNull @Size(min = 1, max = 6) List<@Valid Section> sections,
            @NotBlank @Size(max = 40) String documentType,
            @NotBlank @Size(max = 40) String draftStatus,
            @NotBlank @Size(max = 1000) String draftReason,
            @NotNull @Size(max = 10) List<@NotBlank @Size(max = 500) String> sourceAttachmentNames,
            @NotNull @Size(max = 30) List<@NotBlank @Size(max = 100) String> templateSections,
            @NotNull @Size(max = 8) List<@Valid Section> draftSections,
            @Valid Preparation preparation,
            Integer preparationSchemaVersion
    ) {
        public Proposal(List<Section> sections) {
            this(sections, "REVIEW_REQUIRED", "NOT_APPLICABLE",
                    "기존 분석 결과에는 제안서 판정 정보가 없습니다.",
                    List.of(), List.of(), List.of(), null, 1);
        }

        public Proposal(
                List<Section> sections,
                String documentType,
                String draftStatus,
                String draftReason,
                List<String> sourceAttachmentNames,
                List<String> templateSections,
                List<Section> draftSections
        ) {
            this(sections, documentType, draftStatus, draftReason, sourceAttachmentNames,
                    templateSections, draftSections, null, 1);
        }
    }

    public record Preparation(
            @NotNull @Size(min = 3, max = 8) List<@NotBlank @Size(max = 500) String> meetingAgenda,
            @NotNull @Size(min = 1, max = 12) List<@Valid PreparationItem> eligibilityChecklist,
            @NotNull @Size(min = 1, max = 15) List<@Valid PreparationItem> submissionDocuments,
            @NotNull @Size(min = 1, max = 12) List<@Valid PreparationItem> companyInputs,
            @Size(max = 10) String applicationDeadline,
            @NotNull @Valid StrategyOnePage strategy
    ) {
    }

    public record PreparationItem(
            @NotBlank @Size(max = 150) String title,
            @NotBlank @Size(max = 40) String status,
            @NotBlank @Size(max = 500) String detail,
            @NotBlank @Size(max = 300) String nextAction,
            @Size(max = 40) String requirementLevel,
            @Size(max = 40) String stage,
            @Size(max = 200) String appliesTo,
            @Valid RequirementSource source,
            @Size(max = 40) String companyEvidenceLevel,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer readinessScore,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer conditionScore,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer evidenceScore,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer scheduleScore,
            @Size(max = 40) String workType,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(120) Integer estimatedBusinessDays,
            @Size(max = 4) List<@NotBlank @Size(max = 300) String> scoreBasis
    ) {
    }

    public record RequirementSource(
            @Size(max = 40) String origin,
            @Size(max = 500) String attachmentName,
            @Size(max = 200) String sectionTitle,
            @Size(max = 200) String location,
            @Size(max = 300) String excerpt
    ) {
    }

    public record StrategyOnePage(
            @NotBlank @Size(max = 40) String decision,
            @NotBlank @Size(max = 500) String decisionReason,
            @NotBlank @Size(max = 120) String recommendedProject,
            @NotBlank @Size(max = 500) String recommendedParticipation,
            @NotBlank @Size(max = 500) String alternativeParticipation,
            @NotNull @Size(min = 1, max = 4) List<@Valid StrategyCapabilityMatch> capabilityMatches,
            @NotNull @Size(min = 1, max = 4) List<@Valid StrategyGap> criticalGaps,
            @NotNull @Size(min = 1, max = 4) List<@Valid StrategyStopCriterion> stopCriteria
    ) {
    }

    public record StrategyCapabilityMatch(
            @NotBlank @Size(max = 500) String confirmedFact,
            @NotBlank @Size(max = 500) String strategicInterpretation
    ) {
    }

    public record StrategyGap(
            @NotBlank @Size(max = 300) String gap,
            @NotBlank @Size(max = 300) String nextAction,
            @NotBlank @Size(max = 100) String owner,
            @NotBlank @Size(max = 150) String targetTiming,
            @Size(max = 40) String workType,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(120) Integer estimatedBusinessDays,
            @Size(max = 10) String targetDate,
            @Size(max = 300) String scheduleBasis
    ) {
    }

    public record StrategyStopCriterion(
            @NotBlank @Size(max = 40) String type,
            @NotBlank @Size(max = 400) String condition,
            @NotBlank @Size(max = 400) String rationale
    ) {
    }

    public record Section(
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 1000) String body
    ) {
    }

    public record Opportunity(
            @NotNull @Size(min = 4, max = 4) List<@Valid OpportunityDimension> dimensions
    ) {
        @AssertTrue(message = "기회 점수의 네 평가 항목이 각각 한 번씩 필요합니다.")
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
