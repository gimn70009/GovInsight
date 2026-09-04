package com.publicmonitor.backend.domain.monitoring.client.dto;

import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringJobStatus;
import java.util.UUID;

// Python이 반환한 응답 JSON을 Spring Boot에서 받을 때 사용하는 응답 DTO
public record PythonMonitoringJobResponse(
        UUID jobId,
        PythonMonitoringJobStatus status
) {
}
