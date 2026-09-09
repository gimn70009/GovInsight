package com.publicmonitor.backend.domain.document.web.dto;

import java.util.List;

public record ProposalDraftStateResponse(
        List<SavedProposalDraftResponse> drafts,
        List<RunningProposalResponse> running) {
    public record RunningProposalResponse(Long attachmentId, int partIndex) {}
}
