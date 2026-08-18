package com.publicmonitor.backend.domain.document.web.dto;

import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import java.util.List;

public record CollectionResultResponse(List<DocumentResult> documents) {

    public record DocumentResult(
            String originalUrl,
            Long documentId,
            Long versionId,
            DocumentChangeType changeType,
            boolean analysisRequired
    ) {
    }
}
