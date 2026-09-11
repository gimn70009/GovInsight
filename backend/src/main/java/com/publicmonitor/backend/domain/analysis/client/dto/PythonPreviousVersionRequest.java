package com.publicmonitor.backend.domain.analysis.client.dto;

import java.util.List;

public record PythonPreviousVersionRequest(
        Long versionId,
        String title,
        String contentText,
        List<PythonAnalysisAttachmentRequest> attachments
) {
}
