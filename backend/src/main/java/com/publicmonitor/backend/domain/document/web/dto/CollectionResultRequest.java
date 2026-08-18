package com.publicmonitor.backend.domain.document.web.dto;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
            @NotBlank @Size(max = 2000) String downloadUrl,
            @Size(max = 200) String contentType,
            @PositiveOrZero Long fileSize,
            @Pattern(regexp = "[0-9a-f]{64}") String fileHash,
            String extractedText,
            @NotNull AttachmentParseStatus parseStatus,
            @Size(max = 2000) String errorMessage
    ) {
        public CollectedAttachment(
                String fileName,
                String downloadUrl,
                String contentType,
                Long fileSize,
                String fileHash,
                AttachmentParseStatus parseStatus,
                String errorMessage
        ) {
            this(fileName, downloadUrl, contentType, fileSize, fileHash, null, parseStatus, errorMessage);
        }

        public CollectedAttachment(String fileName, String downloadUrl) {
            this(fileName, downloadUrl, null, null, null, null, AttachmentParseStatus.PENDING, null);
        }
    }
}