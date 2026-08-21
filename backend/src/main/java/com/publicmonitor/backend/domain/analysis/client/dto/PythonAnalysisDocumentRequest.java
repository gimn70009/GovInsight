package com.publicmonitor.backend.domain.analysis.client.dto;

import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import java.time.LocalDateTime;
import java.util.List;

public record PythonAnalysisDocumentRequest(
        Long detectionId,
        Long documentId,
        Long versionId,
        DocumentChangeType changeType,
        String organizationName,
        String boardName,
        String title,
        String contentText,
        LocalDateTime publishedAt,
        String originalUrl,
        List<PythonAnalysisAttachmentRequest> attachments,
        PythonPreviousVersionRequest previousVersion
) {
}
