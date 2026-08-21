package com.publicmonitor.backend.domain.report.client.dto;

import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import java.time.LocalDateTime;
import java.util.List;

public record PythonReportDocumentRequest(
        Long detectionId,
        Long documentId,
        Long versionId,
        String organizationName,
        String boardName,
        DocumentChangeType changeType,
        String title,
        LocalDateTime publishedAt,
        String originalUrl,
        String summary,
        List<String> keyPoints,
        DocumentImportance importance,
        String reason
) {
}
