package com.publicmonitor.backend.domain.document.web.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record ProposalRestoreRequest(@NotNull @Min(1) Long attachmentId, @Min(0) int partIndex,
        @NotNull @Min(0) Long expectedRevision, @NotNull UUID operationId) {
    public ProposalWriteRequest source() { return new ProposalWriteRequest(attachmentId, partIndex); }
}
