package com.publicmonitor.backend.domain.document.web.dto;

import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import java.time.LocalDateTime;

public record DocumentDetectionSummaryRow(
        Long runId,
        Long detectionId,
        Long documentId,
        Long versionId,
        String organizationName,
        String boardName,
        String title,
        DocumentChangeType changeType,
        int attachmentCount,
        DocumentImportance importance,
        Integer opportunityScore,
        String opportunityAssessment,
        LocalDateTime lastCheckedAt
) {
}