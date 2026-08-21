package com.publicmonitor.backend.domain.report.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ReportResultRequest(
        @NotNull @Positive Long runId,
        @NotNull UUID jobId,
        @NotNull ReportResultStatus status,
        @Size(max = 500) String title,
        @Size(max = 20000) String summary,
        @Size(max = 2000) String errorMessage
) {
}
