package com.publicmonitor.backend.domain.document.web.dto;

import java.util.List;

public record ProposalSourceResponse(Long attachmentId, int partIndex, String fileName,
        String attachmentName, boolean available, String reason, List<String> relatedFileNames) {
    public ProposalSourceResponse(Long attachmentId, int partIndex, String fileName,
            String attachmentName, boolean available, String reason) {
        this(attachmentId, partIndex, fileName, attachmentName, available, reason, List.of());
    }
}
