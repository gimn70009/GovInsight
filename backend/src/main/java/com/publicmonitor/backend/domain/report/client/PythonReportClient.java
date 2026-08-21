package com.publicmonitor.backend.domain.report.client;

import com.publicmonitor.backend.domain.report.client.dto.PythonReportJobRequest;
import com.publicmonitor.backend.domain.report.client.dto.PythonReportJobResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class PythonReportClient {

    private final RestClient restClient;

    public PythonReportClient(@Qualifier("pythonMonitoringRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public PythonReportJobResponse accept(PythonReportJobRequest request) {
        try {
            ResponseEntity<PythonReportJobResponse> response = restClient.post()
                    .uri("/internal/monitoring/report-jobs")
                    .body(request)
                    .retrieve()
                    .toEntity(PythonReportJobResponse.class);

            if (response.getStatusCode() != HttpStatus.ACCEPTED || !isValid(response.getBody(), request)) {
                throw new PythonReportClientException("Python 보고서 작업 접수 응답이 올바르지 않습니다.");
            }
            return response.getBody();
        } catch (RestClientException exception) {
            log.error("Python 보고서 생성 요청에 실패했습니다. runId={}", request.runId(), exception);
            throw new PythonReportClientException("Python 보고서 생성 요청에 실패했습니다.", exception);
        }
    }

    private boolean isValid(PythonReportJobResponse response, PythonReportJobRequest request) {
        return response != null
                && response.jobId() != null
                && response.status() == PythonReportJobStatus.ACCEPTED
                && response.documentCount() == request.documents().size();
    }
}
