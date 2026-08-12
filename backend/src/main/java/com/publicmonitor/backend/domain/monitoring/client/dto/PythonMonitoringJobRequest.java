package com.publicmonitor.backend.domain.monitoring.client.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import java.util.List;

// Python으로 보낼 전체 요청 JSON을 만드는 DTO
public record PythonMonitoringJobRequest(
        Long runId,
        List<PythonMonitoringSourceRequest> sources
) {

    public static PythonMonitoringJobRequest of(Long runId, List<MonitoringSource> sources) {
        return new PythonMonitoringJobRequest(
                runId,
                sources.stream()
                        .map(PythonMonitoringSourceRequest::from)
                        .toList()
        );
    }
}
