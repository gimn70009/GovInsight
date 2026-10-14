package com.publicmonitor.backend.domain.document.web.dto;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisFavorability;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.global.presentation.YearNotation;
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
        @Schema(description = "같은 문서 버전의 최신 감지 실행에서 분석·제안 처리가 진행될 수 있으면 true")
        boolean analysisPending,
        List<Attachment> attachments,
        @Schema(description = "현재 본문·첨부에 역할과 이름이 명시된 사업 담당기관. 근거가 없으면 빈 목록")
        List<ResponsibleOrganization> responsibleOrganizations,
        @Schema(description = "원문의 명시된 제출서류와 기존 준비서류를 통합한 안내. 분석 완료 여부와 독립적")
        SubmissionGuide submissionGuide
) {

    @Schema(description = "현재 공고 원문에서 확인한 기관과 역할. 문의처를 주관기관으로 바꾸지 않음")
    public record ResponsibleOrganization(
            String name, String role, String sourceType, String attachmentName, String excerpt
    ) {}

    public record SubmissionGuide(List<SubmissionDocument> documents, List<SubmissionNote> notes) {}

    public record SubmissionDocument(
            String title, String detail, String requirementLevel, String stage, String appliesTo,
            RequirementSource source,
            @Schema(description = "명시된 개별 서류명. 파일 형식이나 설명 문장은 포함하지 않음") List<String> documentNames,
            @Schema(description = "서류별 제출 대상·예외·대체 제출 조건") List<String> conditions,
            @Schema(description = "해당 서류에 명시된 파일 형식") List<String> formats
    ) {
        public SubmissionDocument(String title, String detail, String requirementLevel, String stage, String appliesTo, RequirementSource source) {
            this(title, detail, requirementLevel, stage, appliesTo, source, List.of(), List.of(), List.of());
        }
        public SubmissionDocument {
            title = YearNotation.display(title);
            detail = YearNotation.display(detail);
            appliesTo = YearNotation.display(appliesTo);
            documentNames = documentNames == null ? List.of() : documentNames.stream().map(YearNotation::display).toList();
            conditions = conditions == null ? List.of() : conditions.stream().map(YearNotation::display).toList();
            formats = formats == null ? List.of() : List.copyOf(formats);
        }
    }

    @Schema(description = "원문의 제출 조건을 읽기 쉽게 정리한 안내. appliesTo는 명시적으로 연결된 서류명이며 공통 안내는 null. source에는 수정하지 않은 원문을 보존")
    public record SubmissionNote(String text, String appliesTo, RequirementSource source) {
        public SubmissionNote {
            text = YearNotation.display(text);
            appliesTo = YearNotation.display(appliesTo);
        }
    }

    @Schema(description = "문서별 AI 분석", nullable = true)
    public record Analysis(
            String summary,
            List<String> keyPoints,
            DocumentImportance importance,
            String reason,
            AnalysisEligibility eligibility,
            AnalysisFavorability favorableOrNot,
            Proposal proposal,
            Opportunity opportunity,
            String applicationDeadline,
            BusinessSummary businessSummary
    ) {
        public Analysis {
            summary = YearNotation.display(summary);
            keyPoints = displayLines(keyPoints);
            reason = YearNotation.display(reason);
            applicationDeadline = YearNotation.display(applicationDeadline);
        }
    }

    @Schema(description = "저장된 공고 분석의 사업 요약. 별도 재분석 없이 제공하며 미확인 값은 null")
    public record BusinessSummary(String purpose, String supportScale) {
        public BusinessSummary {
            purpose = YearNotation.display(purpose);
            supportScale = YearNotation.display(supportScale);
        }
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
            Integer preparationSchemaVersion,
            Boolean usesDemoProfile
    ) {
        public Proposal {
            draftReason = YearNotation.display(draftReason);
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
            List<String> meetingAgenda,
            List<String> submissionReviewNotes,
            List<PreparationItem> eligibilityChecklist,
            List<PreparationItem> submissionDocuments,
            String applicationDeadline,
            StrategyOnePage strategy
    ) {
        public Preparation {
            meetingAgenda = displayLines(meetingAgenda);
            submissionReviewNotes = displayLines(submissionReviewNotes);
            applicationDeadline = YearNotation.display(applicationDeadline);
        }
    }

    public record PreparationItem(
            String title,
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
        public PreparationItem {
            title = YearNotation.display(title);
            detail = YearNotation.display(detail);
            nextAction = YearNotation.display(nextAction);
            appliesTo = YearNotation.display(appliesTo);
            scoreBasis = displayLines(scoreBasis);
        }
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

    public record StrategyCapabilityMatch(String companyEvidenceId, String confirmedFact, String strategicInterpretation) {
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
        public Section {
            title = YearNotation.display(title);
            body = YearNotation.display(body);
        }
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
        public OpportunityDimension {
            reason = YearNotation.display(reason);
        }
    }

    private static List<String> displayLines(List<String> values) {
        return values == null ? null : values.stream().map(YearNotation::display).toList();
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
