package com.publicmonitor.backend.domain.document.web.dto;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisFavorability;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "감지 문서 상세")
public record DocumentDetectionDetailResponse(
        Long detectionId,
        String organizationName,
        String boardName,
        String title,
        LocalDateTime publishedAt,
        DocumentChangeType changeType,
        String originalUrl,
        LocalDateTime lastCheckedAt,
        Analysis analysis,
        List<Attachment> attachments
) {

    @Schema(description = "문서별 AI 분석", nullable = true)
    public record Analysis(
            String summary,
            List<String> keyPoints,
            DocumentImportance importance,
            String reason,
            AnalysisEligibility eligibility,
            AnalysisFavorability favorableOrNot,
            Proposal proposal,
            Opportunity opportunity
    ) {
    }

    @Schema(description = "회사 활용·대응 전략")
    public record Proposal(
            List<Section> sections,
            String documentType,
            String draftStatus,
            String draftReason,
            List<String> sourceAttachmentNames,
            List<String> templateSections,
            List<Section> draftSections,
            Preparation preparation,
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
            List<String> meetingAgenda,
            List<PreparationItem> eligibilityChecklist,
            List<PreparationItem> submissionDocuments,
            List<PreparationItem> companyInputs,
            String applicationDeadline,
            StrategyOnePage strategy
    ) {
    }

    public record PreparationItem(
            String title,
            String status,
            String detail,
            String nextAction,
            String requirementLevel,
            String stage,
            String appliesTo,
            RequirementSource source,
            String companyEvidenceLevel,
            Integer readinessScore,
            Integer conditionScore,
            Integer evidenceScore,
            Integer scheduleScore,
            String workType,
            Integer estimatedBusinessDays,
            List<String> scoreBasis
    ) {
    }

    public record RequirementSource(
            String origin,
            String attachmentName,
            String sectionTitle,
            String location,
            String excerpt
    ) {
    }

    public record StrategyOnePage(
            String decision,
            String decisionReason,
            String recommendedProject,
            String recommendedParticipation,
            String alternativeParticipation,
            List<StrategyCapabilityMatch> capabilityMatches,
            List<Object> criticalGaps,
            List<Object> stopCriteria,
            List<String> selectionRationale,
            String projectTitle,
            String problem,
            String companyRole,
            String partnerRole,
            String solution,
            List<String> kpis,
            List<String> phases,
            List<String> risks
    ) {
    }

    public record StrategyCapabilityMatch(String confirmedFact, String strategicInterpretation) {
    }

    public record StrategyGap(
            String gap,
            String nextAction,
            String owner,
            String targetTiming,
            String workType,
            Integer estimatedBusinessDays,
            String targetDate,
            String scheduleBasis
    ) {
    }

    public record StrategyStopCriterion(String type, String condition, String rationale) {
    }

    @Schema(description = "회사 활용·대응 전략의 한 단계")
    public record Section(String title, String body) {
    }

    @Schema(description = "AI 기회 점수와 대응 우선순위", nullable = true)
    public record Opportunity(
            Integer totalScore,
            OpportunityPriority priority,
            List<OpportunityDimension> dimensions
    ) {
    }

    @Schema(description = "기회 점수 평가 항목")
    public record OpportunityDimension(
            OpportunityDimensionType type,
            Integer score,
            String reason
    ) {
    }

    @Schema(description = "첨부파일")
    public record Attachment(
            String fileName,
            String fileExtension,
            Long fileSize,
            AttachmentParseStatus parseStatus,
            String downloadUrl
    ) {
    }
}
