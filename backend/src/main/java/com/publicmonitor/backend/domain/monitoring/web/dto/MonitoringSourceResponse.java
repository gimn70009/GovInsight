package com.publicmonitor.backend.domain.monitoring.web.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import java.time.LocalDateTime;

public record MonitoringSourceResponse(
        Long sourceId,
        String organizationName,
        String boardName,
        String description,
        String listUrl,
        String urlIncludePattern,
        int detailFetchCount,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static MonitoringSourceResponse from(MonitoringSource source) {
        return new MonitoringSourceResponse(
                source.getId(),
                source.getOrganizationName(),
                source.getBoardName(),
                source.getDescription(),
                source.getListUrl(),
                source.getUrlIncludePattern(),
                source.getDetailFetchCount(),
                source.isEnabled(),
                source.getCreatedAt(),
                source.getUpdatedAt()
        );
    }
}
