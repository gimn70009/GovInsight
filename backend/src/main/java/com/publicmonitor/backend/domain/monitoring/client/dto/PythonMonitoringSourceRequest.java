package com.publicmonitor.backend.domain.monitoring.client.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;

// Python에 보내는 요청 중에서 모니터링 소스 한 건을 표현하는 요청 DTO
public record PythonMonitoringSourceRequest(
        Long sourceId,
        String organizationName,
        String boardName,
        String listUrl,
        String urlIncludePattern,
        int detailFetchCount
) {

    public static PythonMonitoringSourceRequest from(MonitoringSource source) {
        return new PythonMonitoringSourceRequest(
                source.getId(),
                source.getOrganizationName(),
                source.getBoardName(),
                source.getListUrl(),
                source.getUrlIncludePattern(),
                source.getDetailFetchCount()
        );
    }
}
