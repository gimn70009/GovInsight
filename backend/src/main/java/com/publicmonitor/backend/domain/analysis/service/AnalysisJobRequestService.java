package com.publicmonitor.backend.domain.analysis.service;

import com.publicmonitor.backend.domain.analysis.client.dto.PythonAnalysisAttachmentRequest;
import com.publicmonitor.backend.domain.analysis.client.dto.PythonAnalysisDocumentRequest;
import com.publicmonitor.backend.domain.analysis.client.dto.PythonAnalysisJobRequest;
import com.publicmonitor.backend.domain.analysis.client.dto.PythonPreviousAnalysisRequest;
import com.publicmonitor.backend.domain.analysis.client.dto.PythonPreviousVersionRequest;
import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentDetectionRepository;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentVersionRepository;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalysisJobRequestService {

    private final AnalysisDocumentDetectionRepository detectionRepository;
    private final DocumentAttachmentRepository attachmentRepository;
    private final AnalysisDocumentVersionRepository versionRepository;
    private final DocumentAnalysisRepository analysisRepository;

    @Transactional(readOnly = true)
    public Optional<PythonAnalysisJobRequest> prepare(Long runId) {
        List<PythonAnalysisDocumentRequest> documents = detectionRepository
                .findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(runId).stream()
                .filter(this::requiresAnalysis)
                .map(this::toRequest)
                .filter(this::hasAnalyzableText)
                .toList();

        return documents.isEmpty()
                ? Optional.empty()
                : Optional.of(new PythonAnalysisJobRequest(runId, documents));
    }

    private boolean requiresAnalysis(DocumentDetection detection) {
        if (detection.getChangeType() != DocumentChangeType.UNCHANGED_DOCUMENT) {
            return true;
        }
        return analysisRepository.findByDocumentVersionId(detection.getDocumentVersion().getId())
                .map(DocumentAnalysis::requiresProposalSchemaUpgrade)
                .orElse(true);
    }

    private PythonAnalysisDocumentRequest toRequest(DocumentDetection detection) {
        DocumentVersion version = detection.getDocumentVersion();
        List<PythonAnalysisAttachmentRequest> attachments = attachmentRepository
                .findAllByDocumentVersionId(version.getId()).stream()
                .filter(attachment -> attachment.getParseStatus() == AttachmentParseStatus.COMPLETED)
                .map(this::toAttachmentRequest)
                .toList();

        return new PythonAnalysisDocumentRequest(
                detection.getId(),
                detection.getDocument().getId(),
                version.getId(),
                detection.getChangeType(),
                detection.getMonitoringRunSource().getMonitoringSource().getOrganizationName(),
                detection.getMonitoringRunSource().getMonitoringSource().getBoardName(),
                version.getTitle(),
                version.getContentText(),
                version.getPublishedAt(),
                detection.getDocument().getOriginalUrl(),
                attachments,
                previousVersion(version).orElse(null),
                previousAnalysis(version).orElse(null)
        );
    }

    private boolean hasAnalyzableText(PythonAnalysisDocumentRequest document) {
        boolean hasContent = document.contentText() != null && !document.contentText().isBlank();
        boolean hasAttachmentText = document.attachments().stream()
                .map(PythonAnalysisAttachmentRequest::extractedText)
                .anyMatch(text -> text != null && !text.isBlank());
        return hasContent || hasAttachmentText;
    }

    private PythonAnalysisAttachmentRequest toAttachmentRequest(DocumentAttachment attachment) {
        return new PythonAnalysisAttachmentRequest(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getExtractedText()
        );
    }

    private Optional<PythonPreviousVersionRequest> previousVersion(DocumentVersion version) {
        if (version.getVersionNo() <= 1) {
            return Optional.empty();
        }
        return versionRepository
                .findByDocumentIdAndVersionNo(version.getDocument().getId(), version.getVersionNo() - 1)
                .map(previous -> new PythonPreviousVersionRequest(
                        previous.getId(),
                        previous.getTitle(),
                        previous.getContentText()
                ));
    }

    private Optional<PythonPreviousAnalysisRequest> previousAnalysis(DocumentVersion version) {
        if (version.getVersionNo() <= 1) {
            return Optional.empty();
        }
        return versionRepository
                .findByDocumentIdAndVersionNo(version.getDocument().getId(), version.getVersionNo() - 1)
                .flatMap(previous -> analysisRepository.findByDocumentVersionId(previous.getId()))
                .map(analysis -> new PythonPreviousAnalysisRequest(
                        analysis.getSummary(),
                        analysis.getKeyPoints(),
                        analysis.getEligibility() == null ? null : analysis.getEligibility().name(),
                        analysis.getFavorableOrNot() == null ? null : analysis.getFavorableOrNot().name(),
                        analysis.getProposalDirection()
                ));
    }
}
