package com.publicmonitor.backend.domain.analysis.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ProposalResultRequest(
        @NotNull @Positive Long runId,
        @NotNull UUID jobId,
        @NotNull @Size(min = 1) List<@Valid ProposalUpdate> results
) {

    public record ProposalUpdate(
            @NotNull @Positive Long detectionId,
            @NotNull @Positive Long documentId,
            @NotNull @Positive Long versionId,
            @NotNull @Valid AnalysisResultRequest.Proposal proposal,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 100) String> usedTools
    ) {
    }
}
