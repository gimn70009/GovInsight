package com.publicmonitor.backend.domain.analysis.web.dto;

public record ProposalResultResponse(
        Long runId,
        int updatedProposalCount
) {
}
