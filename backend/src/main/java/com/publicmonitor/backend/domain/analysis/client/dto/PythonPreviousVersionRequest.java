package com.publicmonitor.backend.domain.analysis.client.dto;

public record PythonPreviousVersionRequest(
        Long versionId,
        String title,
        String contentText
) {
}
