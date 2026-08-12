package com.publicmonitor.backend.domain.monitoring.client;

import com.publicmonitor.backend.domain.monitoring.client.dto.PythonMonitoringJobRequest;
import com.publicmonitor.backend.domain.monitoring.client.dto.PythonMonitoringJobResponse;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class PythonMonitoringClient {

    private final RestClient restClient;

    public PythonMonitoringClient(
            @Qualifier("pythonMonitoringRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    public PythonMonitoringJobResponse accept(Long runId, List<MonitoringSource> sources) {
        PythonMonitoringJobRequest request = PythonMonitoringJobRequest.of(runId, sources);

        try {
            ResponseEntity<PythonMonitoringJobResponse> response = restClient.post()
                    .uri("/internal/monitoring/jobs")
                    .body(request)
                    .retrieve()
                    .toEntity(PythonMonitoringJobResponse.class);

            if (response.getStatusCode() != HttpStatus.ACCEPTED || !isValid(response.getBody())) {
                throw new PythonMonitoringClientException("Python 작업 접수 응답이 올바르지 않습니다.");
            }
            return response.getBody();
        } catch (RestClientException exception) {
            log.error("Python 모니터링 작업 접수 요청에 실패했습니다. runId={}", runId, exception);
            throw new PythonMonitoringClientException("Python 작업 접수 요청에 실패했습니다.", exception);
        }
    }

    private boolean isValid(PythonMonitoringJobResponse response) {
        return response != null
                && response.jobId() != null
                && response.status() == PythonMonitoringJobStatus.ACCEPTED;
    }
}
