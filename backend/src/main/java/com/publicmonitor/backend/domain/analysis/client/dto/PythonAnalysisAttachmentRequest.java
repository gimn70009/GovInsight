package com.publicmonitor.backend.domain.analysis.client.dto;

public record PythonAnalysisAttachmentRequest(
        Long attachmentId,
        String fileName,
        String extractedText
) {
}
