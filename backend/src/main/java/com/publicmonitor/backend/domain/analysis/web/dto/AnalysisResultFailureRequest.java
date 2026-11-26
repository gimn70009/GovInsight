package com.publicmonitor.backend.domain.analysis.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record AnalysisResultFailureRequest(
        @NotNull @Positive Long runId,
        @NotNull UUID jobId,
        @NotNull Phase phase,
        @NotNull @Min(400) @Max(499) Integer httpStatus
) {
    public enum Phase { ANALYSIS, PROPOSAL }
}
