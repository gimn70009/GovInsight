package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisTask;
import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisTaskRepository;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.analysis.service.OpportunityScoreCalculator;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.entity.DocumentAnalysisStatus;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@Lazy
@RequiredArgsConstructor
public class DocumentDetectionDetailService {

    private final DocumentDetectionRepository detectionRepository;
    private final DocumentAnalysisRepository analysisRepository;
    private final AnalysisTaskRepository analysisTaskRepository;
    private final DocumentAttachmentRepository attachmentRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DocumentDetectionDetailResponse findById(Long detectionId) {
        DocumentDetection detection = detectionRepository.findById(detectionId)
                .orElseThrow(() -> new DocumentDetectionException(DocumentDetectionResponseCode.NOT_FOUND));
        DocumentVersion version = detection.getDocumentVersion();
        // Read processing state first so a concurrent completion cannot pair old results with a terminal state.
        ProcessingState processing = processingState(detection, version);
        DocumentAnalysis analysis = analysisRepository.findByDocumentVersionId(version.getId()).orElse(null);
        DocumentAnalysisStatus analysisStatus = DocumentAnalysisStatusResolver.resolve(
                analysis != null, processing.runStatus(), processing.taskState());
        boolean analysisPending = analysisStatus != DocumentAnalysisStatus.FAILED
                && DocumentAnalysisStatusResolver.isProcessing(processing.runStatus(), processing.taskState());
        List<DocumentAttachment> storedAttachments = attachmentRepository.findAllByDocumentVersionId(version.getId());
        List<DocumentDetectionDetailResponse.Attachment> attachments = storedAttachments.stream()
                .map(this::toAttachment)
                .toList();

        var analysisResponse = toAnalysis(analysis);
        var preparation = analysisResponse == null ? null : analysisResponse.proposal().preparation();
        var businessType = BusinessTypeResolver.diagnose(version.getTitle(), version.getContentText(), storedAttachments,
                analysis == null ? null : analysis.getComparisonSummary(),
                analysisResponse == null ? null : analysisResponse.proposal().documentType(), objectMapper);
        log.info("Business type resolution. detection_id={} version_id={} analysis_pending={} status={} type_code={} decision_reason={} stored_reason={} source_index={} usable_source_count={} failed_attachment_count={} empty_attachment_count={}",
                detectionId, version.getId(), analysisPending, businessType.summary().status(), businessType.summary().code(),
                businessType.decisionReason(), businessType.storedReason(), businessType.rejectedSourceIndex(),
                businessType.usableSourceCount(), businessType.failedAttachmentCount(), businessType.emptyAttachmentCount());
        return new DocumentDetectionDetailResponse(
                detection.getId(),
                detection.getMonitoringRunSource().getMonitoringSource().getOrganizationName(),
                detection.getMonitoringRunSource().getMonitoringSource().getBoardName(),
                version.getTitle(),
                version.getPublishedAt(),
                detection.getChangeType(),
                detection.getDocument().getOriginalUrl(),
                detection.getDocument().getLastDetectedAt(),
                analysisResponse,
                analysisPending,
                analysisStatus,
                analysisStatus == DocumentAnalysisStatus.FAILED ? failureReason(version, storedAttachments) : null,
                attachments,
                ResponsibleOrganizationExtractor.extract(version.getContentText(), storedAttachments),
                SubmissionDocumentResolver.resolve(version.getContentText(), storedAttachments, preparation),
                ParticipationConditionResolver.resolve(version.getContentText(), storedAttachments, preparation),
                businessType.summary()
        );
    }

    private ProcessingState processingState(DocumentDetection detection, DocumentVersion version) {
        DocumentDetection latestDetection = detectionRepository
                .findTopByDocumentVersionIdOrderByDetectedAtDescIdDesc(version.getId())
                .orElse(detection);
        MonitoringRun run = latestDetection.getMonitoringRunSource().getMonitoringRun();
        if (!MonitoringRunStatus.inProgress().contains(run.getStatus())) {
            return new ProcessingState(run.getStatus(), null);
        }
        return new ProcessingState(run.getStatus(), analysisTaskRepository.findByRunId(run.getId())
                .map(AnalysisTask::getState).orElse(null));
    }

    private record ProcessingState(MonitoringRunStatus runStatus, AnalysisTask.State taskState) {}

    private String failureReason(DocumentVersion version, List<DocumentAttachment> attachments) {
        boolean hasText = version.getContentText() != null && !version.getContentText().isBlank();
        boolean hasAttachmentText = attachments.stream()
                .filter(attachment -> attachment.getParseStatus() == AttachmentParseStatus.COMPLETED)
                .anyMatch(attachment -> attachment.getExtractedText() != null
                        && !attachment.getExtractedText().isBlank());
        return !hasText && !hasAttachmentText
                ? "본문·첨부파일에서 읽을 텍스트를 찾지 못했어요. 이미지로 된 공고일 수 있으니 원문을 확인해 주세요."
                : "분석을 완료하지 못했어요. 원문을 확인하거나 다시 모니터링해 주세요.";
    }

    private DocumentDetectionDetailResponse.Analysis toAnalysis(DocumentAnalysis analysis) {
        if (analysis == null) {
            return null;
        }
        return new DocumentDetectionDetailResponse.Analysis(
                analysis.getSummary(),
                parseKeyPoints(analysis.getKeyPoints()),
                analysis.getImportance(),
                analysis.getReason(),
                analysis.getEligibility(),
                analysis.getFavorableOrNot(),
                parseProposal(analysis.getProposalDirection()),
                parseOpportunity(analysis),
                parseApplicationDeadline(analysis.getComparisonSummary()),
                parseBusinessSummary(analysis.getComparisonSummary())
        );
    }

    private DocumentDetectionDetailResponse.BusinessSummary parseBusinessSummary(String comparisonSummary) {
        if (comparisonSummary == null || comparisonSummary.isBlank()) return null;
        try {
            var summary = objectMapper.readTree(comparisonSummary);
            var purpose = summary.path("purpose");
            var supportScale = summary.path("supportScale");
            return new DocumentDetectionDetailResponse.BusinessSummary(
                    purpose.isString() ? purpose.asString().strip() : null,
                    supportScale.isString() ? supportScale.asString().strip() : null
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String parseApplicationDeadline(String comparisonSummary) {
        if (comparisonSummary == null || comparisonSummary.isBlank()) return null;
        try {
            var value = objectMapper.readTree(comparisonSummary).path("applicationDeadline");
            return value.isString() ? value.asString().strip() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private DocumentDetectionDetailResponse.Proposal parseProposal(String proposalDirection) {
        if (proposalDirection == null || proposalDirection.isBlank()) {
            return new DocumentDetectionDetailResponse.Proposal(List.of());
        }
        String normalized = proposalDirection.strip();
        if (!normalized.startsWith("{")) {
            return new DocumentDetectionDetailResponse.Proposal(List.of(
                    new DocumentDetectionDetailResponse.Section("기존 제안 방향", normalized)
            ));
        }
        DocumentDetectionDetailResponse.Proposal proposal = objectMapper.readValue(
                PreparationCompatibility.normalize(normalized, objectMapper),
                DocumentDetectionDetailResponse.Proposal.class
        );
        return new DocumentDetectionDetailResponse.Proposal(
                proposal.sections() == null ? List.of() : proposal.sections(),
                proposal.documentType() == null ? "REVIEW_REQUIRED" : proposal.documentType(),
                proposal.draftStatus() == null ? "NOT_APPLICABLE" : proposal.draftStatus(),
                proposal.draftReason() == null
                        ? "기존 분석 결과에는 제안서 판정 정보가 없습니다."
                        : proposal.draftReason(),
                proposal.sourceAttachmentNames() == null ? List.of() : proposal.sourceAttachmentNames(),
                proposal.templateSections() == null ? List.of() : proposal.templateSections(),
                proposal.draftSections() == null ? List.of() : proposal.draftSections(),
                proposal.preparation(),
                proposal.preparationSchemaVersion() == null ? 1 : proposal.preparationSchemaVersion(),
                proposal.usesDemoProfile()
        );
    }

    private DocumentDetectionDetailResponse.Opportunity parseOpportunity(DocumentAnalysis analysis) {
        if (analysis.getOpportunityAssessment() == null || analysis.getOpportunityAssessment().isBlank()) {
            return null;
        }
        StoredOpportunity stored = objectMapper.readValue(
                analysis.getOpportunityAssessment(),
                StoredOpportunity.class
        );
        List<DocumentDetectionDetailResponse.OpportunityDimension> dimensions =
                stored.dimensions().stream()
                        .map(this::toOpportunityDimension)
                        .filter(Objects::nonNull)
                        .toList();
        Map<OpportunityDimensionType, Integer> scores = dimensions.stream()
                .collect(Collectors.toMap(
                        DocumentDetectionDetailResponse.OpportunityDimension::type,
                        DocumentDetectionDetailResponse.OpportunityDimension::score
                ));
        var preset = com.publicmonitor.backend.domain.analysis.service.OpportunitySettingsMetadata.preset(
                objectMapper.readTree(analysis.getOpportunityAssessment()));
        int totalScore = OpportunityScoreCalculator.calculate(scores, preset);
        int companyFitScore = scores.getOrDefault(OpportunityDimensionType.COMPANY_FIT, 0);
        int feasibilityScore = scores.getOrDefault(OpportunityDimensionType.FEASIBILITY, 0);
        int urgencyScore = scores.getOrDefault(OpportunityDimensionType.URGENCY, 0);
        return new DocumentDetectionDetailResponse.Opportunity(
                totalScore,
                OpportunityScoreCalculator.priority(
                        totalScore,
                        companyFitScore,
                        feasibilityScore,
                        urgencyScore
                ),
                dimensions,
                stored.settingsRevision() == null ? 0 : stored.settingsRevision(),
                preset.weights()
        );
    }

    private DocumentDetectionDetailResponse.OpportunityDimension toOpportunityDimension(
            StoredDimension stored
    ) {
        try {
            return new DocumentDetectionDetailResponse.OpportunityDimension(
                    OpportunityDimensionType.valueOf(stored.type()),
                    stored.score(),
                    stored.reason()
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record StoredOpportunity(List<StoredDimension> dimensions, Long settingsRevision, String preset) {
    }

    private record StoredDimension(String type, Integer score, String reason) {
    }

    private DocumentDetectionDetailResponse.Attachment toAttachment(DocumentAttachment attachment) {
        return new DocumentDetectionDetailResponse.Attachment(
                attachment.getFileName(),
                attachment.getFileExtension(),
                attachment.getFileSize(),
                attachment.getParseStatus(),
                attachment.getDownloadUrl()
        );
    }

    private List<String> parseKeyPoints(String keyPoints) {
        if (keyPoints == null || keyPoints.isBlank()) {
            return List.of();
        }
        return Arrays.asList(objectMapper.readValue(keyPoints, String[].class));
    }
}
