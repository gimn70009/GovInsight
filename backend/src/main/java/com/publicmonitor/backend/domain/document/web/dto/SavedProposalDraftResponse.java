package com.publicmonitor.backend.domain.document.web.dto;

import java.time.LocalDateTime;

public record SavedProposalDraftResponse(Long attachmentId, int partIndex, String attachmentName,
        LocalDateTime createdAt, LocalDateTime lastViewedAt, ProposalWriteResponse result,
        long revision, boolean canRestorePrevious, String lastOperationId) {
    public SavedProposalDraftResponse(Long attachmentId, int partIndex, String attachmentName,
            LocalDateTime createdAt, LocalDateTime lastViewedAt, ProposalWriteResponse result) {
        this(attachmentId, partIndex, attachmentName, createdAt, lastViewedAt, result, 0, false, null);
    }
}
