package com.publicmonitor.backend.domain.document.web.dto;

public record ProposalSourceResponse(Long attachmentId, int partIndex, String fileName,
        String attachmentName, boolean available, String reason) {}
