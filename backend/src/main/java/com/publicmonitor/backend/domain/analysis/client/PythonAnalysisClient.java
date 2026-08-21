package com.publicmonitor.backend.domain.analysis.client;

import com.publicmonitor.backend.domain.analysis.client.dto.PythonAnalysisJobRequest;
import com.publicmonitor.backend.domain.analysis.client.dto.PythonAnalysisJobResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class PythonAnalysisClient {

    private final RestClient restClient;

    public PythonAnalysisClient(
            @Qualifier("pythonMonitoringRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    public PythonAnalysisJobResponse accept(PythonAnalysisJobRequest request) {
        try {
            ResponseEntity<PythonAnalysisJobResponse> response = restClient.post()
                    .uri("/internal/monitoring/analysis-jobs")
                    .body(request)
                    .retrieve()
                    .toEntity(PythonAnalysisJobResponse.class);

            if (response.getStatusCode() != HttpStatus.ACCEPTED || !isValid(response.getBody(), request)) {
                throw new PythonAnalysisClientException("Python 분석 작업 접수 응답이 올바르지 않습니다.");
            }
            return response.getBody();
        } catch (RestClientException exception) {
            log.error("Python 분석 작업 접수 요청에 실패했습니다. runId={}", request.runId(), exception);
            throw new PythonAnalysisClientException("Python 분석 작업 접수 요청에 실패했습니다.", exception);
        }
    }

    private boolean isValid(PythonAnalysisJobResponse response, PythonAnalysisJobRequest request) {
        return response != null
                && response.jobId() != null
                && response.status() == PythonAnalysisJobStatus.ACCEPTED
                && response.documentCount() == request.documents().size();
    }
}
