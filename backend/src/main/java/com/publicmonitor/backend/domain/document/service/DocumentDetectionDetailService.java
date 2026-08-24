package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
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
                analysis.getProposalDirection()
        );
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
