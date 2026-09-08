package com.publicmonitor.backend.domain.document.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ProposalWriteRequest(@NotNull @Min(1) Long attachmentId, @Min(0) int partIndex) {}
