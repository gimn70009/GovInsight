package com.publicmonitor.backend.domain.document.web.dto;

import java.util.List;

public record ProposalDraftStateResponse(
        List<SavedProposalDraftResponse> drafts,
        List<RunningProposalResponse> running) {
    public record RunningProposalResponse(Long attachmentId, int partIndex, String operationId, String kind) {
        public RunningProposalResponse(Long attachmentId, int partIndex) {
            this(attachmentId, partIndex, null, "GENERATE");
        }
    }
}
