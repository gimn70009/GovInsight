package com.publicmonitor.backend.domain.document.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public record CollectionResultRequest(
        @NotNull @Positive Long runId,
        @NotBlank @Size(max = 100) String jobId,
        @NotEmpty List<@Valid SourceResult> sources
) {

    public record SourceResult(
            @NotNull @Positive Long sourceId,
            @NotNull CollectionSourceStatus status,
            @Size(max = 2000) String errorMessage,
            @NotNull List<@Valid CollectedDocument> documents
    ) {
    }

    public record CollectedDocument(
            @NotBlank @Size(max = 2000) String originalUrl,
            @Size(max = 200) String externalDocumentId,
            @NotBlank @Size(max = 500) String title,
            String contentText,
            LocalDateTime publishedAt,
            @NotNull List<@Valid CollectedAttachment> attachments
    ) {
    }

    public record CollectedAttachment(
            @NotBlank @Size(max = 500) String fileName,
            @NotBlank @Size(max = 2000) String downloadUrl
    ) {
    }
}
