package com.publicmonitor.backend.domain.document.web.dto;

import java.time.LocalDateTime;

public record SavedProposalDraftResponse(Long attachmentId, int partIndex, String attachmentName,
        LocalDateTime createdAt, LocalDateTime lastViewedAt, ProposalWriteResponse result) {}
