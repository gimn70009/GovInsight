package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.analysis.service.OpportunityScoreCalculator;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Lazy
@RequiredArgsConstructor
public class DocumentDetectionDetailService {

    private final DocumentDetectionRepository detectionRepository;
    private final DocumentAnalysisRepository analysisRepository;
    private final DocumentAttachmentRepository attachmentRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DocumentDetectionDetailResponse findById(Long detectionId) {
        DocumentDetection detection = detectionRepository.findById(detectionId)
                .orElseThrow(() -> new DocumentDetectionException(DocumentDetectionResponseCode.NOT_FOUND));
        DocumentVersion version = detection.getDocumentVersion();
        DocumentAnalysis analysis = analysisRepository.findByDocumentVersionId(version.getId()).orElse(null);
        List<DocumentDetectionDetailResponse.Attachment> attachments = attachmentRepository
                .findAllByDocumentVersionId(version.getId()).stream()
                .map(this::toAttachment)
                .toList();

        return new DocumentDetectionDetailResponse(
                detection.getId(),
                detection.getMonitoringRunSource().getMonitoringSource().getOrganizationName(),
                detection.getMonitoringRunSource().getMonitoringSource().getBoardName(),
                version.getTitle(),
                version.getPublishedAt(),
                detection.getChangeType(),
                detection.getDocument().getOriginalUrl(),
                detection.getDocument().getLastDetectedAt(),
                toAnalysis(analysis),
                attachments
        );
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
                parseOpportunity(analysis)
        );
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
                normalized,
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
                proposal.preparationSchemaVersion() == null ? 1 : proposal.preparationSchemaVersion()
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
        Map<OpportunityDimensionType, Integer> scores = stored.dimensions().stream()
                .collect(Collectors.toMap(
                        DocumentDetectionDetailResponse.OpportunityDimension::type,
                        DocumentDetectionDetailResponse.OpportunityDimension::score
                ));
        int totalScore = analysis.getOpportunityScore() != null
                ? analysis.getOpportunityScore()
                : OpportunityScoreCalculator.calculate(scores);
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
                stored.dimensions()
        );
    }

    private record StoredOpportunity(
            List<DocumentDetectionDetailResponse.OpportunityDimension> dimensions
    ) {
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
