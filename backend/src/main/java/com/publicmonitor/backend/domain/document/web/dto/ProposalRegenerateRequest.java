package com.publicmonitor.backend.domain.document.web.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record ProposalRegenerateRequest(@NotNull @Min(1) Long attachmentId, @Min(0) int partIndex,
        @NotNull @Min(0) Long expectedRevision, @NotNull UUID operationId, @Size(max = 2000) String feedback) {
    public ProposalWriteRequest source() { return new ProposalWriteRequest(attachmentId, partIndex); }
}
