package com.publicmonitor.backend.domain.analysis.web.dto;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.document.service.BusinessType;
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
import org.hibernate.validator.constraints.CodePointLength;

@Schema(description = "Python AI 문서 분석 결과 전달 요청")
public record AnalysisResultRequest(
        @NotNull @Positive Long runId,
        @NotNull UUID jobId,
        @NotNull List<@Valid AnalysisResult> results,
        @NotNull List<@Valid AnalysisFailure> failures,
        List<@Valid LegalReviewResult> legalResults
) {

    public AnalysisResultRequest {
        legalResults = legalResults == null ? List.of() : List.copyOf(legalResults);
    }

    public AnalysisResultRequest(Long runId, UUID jobId, List<AnalysisResult> results, List<AnalysisFailure> failures) {
        this(runId, jobId, results, failures, List.of());
    }

    public record LegalReviewResult(
            @NotNull @Positive Long detectionId, @NotNull @Positive Long documentId,
            @NotNull @Positive Long versionId,
            @NotNull @Size(min = 1, max = 5) List<@Valid LegalRiskFinding> legalRisks
    ) {}

    @AssertTrue(message = "분석 성공 또는 실패 결과가 한 건 이상 필요합니다.")
    public boolean hasResult() {
        return results != null && failures != null && (!results.isEmpty() || !failures.isEmpty() || !legalResults.isEmpty());
    }

    public record AnalysisResult(
            @NotNull @Positive Long detectionId,
            @NotNull @Positive Long documentId,
            @NotNull @Positive Long versionId,
            @NotBlank @CodePointLength(max = 4000) String summary,
            @NotNull @Size(min = 1, max = 8) List<@NotBlank @CodePointLength(max = 1000) String> keyPoints,
            @NotNull DocumentImportance importance,
            @NotBlank @CodePointLength(max = 1000) String reason,
            @NotNull AnalysisEligibility eligibility,
            @NotNull AnalysisFavorability favorableOrNot,
            @NotNull @Valid Proposal proposal,
            @NotNull @Valid Opportunity opportunity,
            @NotNull @Size(max = 20) List<@NotBlank @CodePointLength(max = 100) String> usedTools,
            @NotBlank @CodePointLength(max = 100) String modelName,
            @Valid ComparisonSummary comparisonSummary,
            @CodePointLength(max = 8000) String similarityProfile,
            @Size(max = 3072) List<@NotNull Double> similarityEmbedding,
            @CodePointLength(max = 100) String embeddingModelName
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
            @NotBlank @CodePointLength(max = 1500) String purpose,
            @NotBlank @CodePointLength(max = 500) String supportScale,
            @NotBlank @CodePointLength(max = 300) String applicationDeadline,
            @NotBlank @CodePointLength(max = 1000) String eligibility,
            @NotBlank @CodePointLength(max = 1000) String requiredPartner,
            @NotNull @Size(min = 5, max = 5) List<@Valid LegalRiskFinding> legalRisks,
            @Valid BusinessTypeAssessment businessType,
            @Valid BusinessTypeReview businessTypeReview
    ) {
        public ComparisonSummary(String purpose, String supportScale, String applicationDeadline, String eligibility,
                                 String requiredPartner, List<LegalRiskFinding> legalRisks, BusinessTypeAssessment businessType) {
            this(purpose, supportScale, applicationDeadline, eligibility, requiredPartner, legalRisks, businessType, null);
        }
        public ComparisonSummary(String purpose, String supportScale, String applicationDeadline, String eligibility,
                                 String requiredPartner, List<LegalRiskFinding> legalRisks) {
            this(purpose, supportScale, applicationDeadline, eligibility, requiredPartner, legalRisks, null);
        }
    }

    public record BusinessTypeReview(
            @NotBlank @CodePointLength(max = 80) String validatorVersion,
            BusinessType candidateType,
            @NotBlank @Size(max = 40) @jakarta.validation.constraints.Pattern(regexp = "[A-Z_]+") String reason,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(3) Integer sourceIndex,
            @jakarta.validation.constraints.Pattern(regexp = "RESULT|REGULATION|AWARD|DESIGNATION_RECRUITMENT|GENERAL|CONTEST") String noticeKind
    ) {}

    public record BusinessTypeAssessment(
            @NotNull BusinessType primaryType,
            @NotBlank @CodePointLength(max = 500) String explanation,
            @NotNull @Size(max = 5) List<@NotBlank @CodePointLength(max = 300) String> details,
            @NotNull @Size(min = 1, max = 4) List<@Valid BusinessTypeEvidence> sources
    ) {}

    public record BusinessTypeEvidence(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "NOTICE_BODY|ATTACHMENT") String origin,
            @CodePointLength(max = 500) String attachmentName,
            @NotBlank @CodePointLength(min = 5, max = 1000) String excerpt
    ) {}

    public record LegalRiskFinding(
            @NotBlank @CodePointLength(max = 40) String type,
            @NotBlank @CodePointLength(max = 40) String status,
            @NotBlank @CodePointLength(max = 500) String summary,
            @CodePointLength(max = 3000) String evidenceExcerpt,
            @CodePointLength(max = 40) String failureReason
    ) {
        public LegalRiskFinding(String type, String status, String summary, String evidenceExcerpt) {
            this(type, status, summary, evidenceExcerpt, null);
        }
    }
    public record Proposal(
            @NotNull @Size(min = 1, max = 6) List<@Valid Section> sections,
            @NotBlank @CodePointLength(max = 40) String documentType,
            @NotBlank @CodePointLength(max = 40) String draftStatus,
            @NotBlank @CodePointLength(max = 1000) String draftReason,
            @NotNull @Size(max = 10) List<@NotBlank @CodePointLength(max = 500) String> sourceAttachmentNames,
            @NotNull @Size(max = 30) List<@NotBlank @CodePointLength(max = 100) String> templateSections,
            @NotNull @Size(max = 8) List<@Valid Section> draftSections,
            @Valid Preparation preparation,
            Integer preparationSchemaVersion,
            Boolean usesDemoProfile
    ) {
        public Proposal {
            usesDemoProfile = Boolean.TRUE.equals(usesDemoProfile);
        }

        public Proposal(
                List<Section> sections, String documentType, String draftStatus, String draftReason,
                List<String> sourceAttachmentNames, List<String> templateSections,
                List<Section> draftSections, Preparation preparation, Integer preparationSchemaVersion
        ) {
            this(sections, documentType, draftStatus, draftReason, sourceAttachmentNames,
                    templateSections, draftSections, preparation, preparationSchemaVersion, false);
        }

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
            @NotNull @Size(max = 20) List<@NotBlank @CodePointLength(max = 500) String> meetingAgenda,
            @Size(max = 64) List<@NotBlank @CodePointLength(max = 500) String> submissionReviewNotes,
            @NotNull @Size(max = 12) List<@Valid PreparationItem> eligibilityChecklist,
            @NotNull @Size(max = 27) List<@Valid PreparationItem> submissionDocuments,
            @CodePointLength(max = 10) String applicationDeadline,
            @NotNull @Valid StrategyOnePage strategy
    ) {
    }

    public record PreparationItem(
            @NotBlank @CodePointLength(max = 150) String title,
            @NotBlank @CodePointLength(max = 500) String detail,
            @NotBlank @CodePointLength(max = 300) String nextAction,
            @CodePointLength(max = 40) String requirementLevel,
            @CodePointLength(max = 40) String stage,
            @CodePointLength(max = 200) String appliesTo,
            @Valid RequirementSource source,
            @CodePointLength(max = 40) String companyEvidenceLevel,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer readinessScore,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer conditionScore,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer evidenceScore,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer scheduleScore,
            @CodePointLength(max = 40) String workType,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(120) Integer estimatedBusinessDays,
            @Size(max = 4) List<@NotBlank @CodePointLength(max = 300) String> scoreBasis
    ) {
    }

    public record RequirementSource(
            @CodePointLength(max = 40) String origin,
            @CodePointLength(max = 500) String attachmentName,
            @CodePointLength(max = 200) String sectionTitle,
            @CodePointLength(max = 200) String location,
            @CodePointLength(max = 1200) String excerpt
    ) {
    }

    public record StrategyOnePage(
            @NotBlank @CodePointLength(max = 40) String decision,
            @NotBlank @CodePointLength(max = 500) String decisionReason,
            @NotBlank @CodePointLength(max = 120) String recommendedProject,
            @NotBlank @CodePointLength(max = 500) String recommendedParticipation,
            @NotBlank @CodePointLength(max = 500) String alternativeParticipation,
            @NotNull @Size(max = 4) List<@Valid StrategyCapabilityMatch> capabilityMatches,
            @NotNull @Size(min = 1, max = 4) List<@Valid StrategyGap> criticalGaps,
            @NotNull @Size(min = 1, max = 4) List<@Valid StrategyStopCriterion> stopCriteria
    ) {
    }

    public record StrategyCapabilityMatch(
            @CodePointLength(max = 80) String companyEvidenceId,
            @NotBlank @CodePointLength(max = 500) String confirmedFact,
            @NotBlank @CodePointLength(max = 500) String strategicInterpretation
    ) {
    }

    public record StrategyGap(
            @NotBlank @CodePointLength(max = 300) String gap,
            @NotBlank @CodePointLength(max = 300) String nextAction,
            @NotBlank @CodePointLength(max = 100) String owner,
            @NotBlank @CodePointLength(max = 150) String targetTiming,
            @CodePointLength(max = 40) String workType,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(120) Integer estimatedBusinessDays,
            @CodePointLength(max = 10) String targetDate,
            @CodePointLength(max = 300) String scheduleBasis
    ) {
    }

    public record StrategyStopCriterion(
            @NotBlank @CodePointLength(max = 40) String type,
            @NotBlank @CodePointLength(max = 400) String condition,
            @NotBlank @CodePointLength(max = 400) String rationale
    ) {
    }

    public record Section(
            @NotBlank @CodePointLength(max = 100) String title,
            @NotBlank @CodePointLength(max = 1000) String body
    ) {
    }

    public record Opportunity(
            @NotNull @Size(min = 4, max = 4) List<@Valid OpportunityDimension> dimensions,
            @jakarta.validation.constraints.Min(0) Long settingsRevision
    ) {
        public Opportunity { settingsRevision = settingsRevision == null ? 0L : settingsRevision; }
        public Opportunity(List<OpportunityDimension> dimensions) { this(dimensions, 0L); }
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
            @NotBlank @CodePointLength(max = 500) String reason
    ) {
    }

    public record AnalysisFailure(
            @NotNull @Positive Long detectionId,
            @NotNull @Positive Long documentId,
            @NotNull @Positive Long versionId,
            @NotBlank @CodePointLength(max = 2000) String errorMessage
    ) {
    }
}
