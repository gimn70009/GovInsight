package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.web.dto.LegalPairResponse;
import com.publicmonitor.backend.domain.document.web.dto.SimilarNoticeResponse;
import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClientProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
@org.springframework.context.annotation.Lazy
public class LegalPairService {
    private final SimilarNoticeService similarNoticeService;
    private final RestClient client;

    public LegalPairService(SimilarNoticeService similarNoticeService, PythonMonitoringClientProperties properties) {
        this.similarNoticeService = similarNoticeService;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(Duration.ofSeconds(35));
        this.client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    // The database read finishes before waiting on the model service.
    public LegalPairResponse compare(Long currentId, Long similarId) {
        var comparison = similarNoticeService.find(currentId);
        var candidate = comparison.similarNotices().stream().filter(item -> item.detectionId().equals(similarId))
                .findFirst().orElseThrow(() -> new DocumentDetectionException(DocumentDetectionResponseCode.NOT_FOUND));
        try {
            var response = client.post().uri("/internal/monitoring/legal-pair-review")
                    .body(new PairRequest(comparison.currentNotice(), candidate.comparison(), candidate.legalReview().checks()))
                    .retrieve().body(LegalPairResponse.class);
            return response == null || response.insights() == null ? LegalPairResponse.unavailable() : response;
        } catch (RestClientException exception) {
            return LegalPairResponse.unavailable();
        }
    }

    private record PairRequest(SimilarNoticeResponse.ComparisonSide current,
            SimilarNoticeResponse.ComparisonSide similar, List<SimilarNoticeResponse.LegalRiskCheck> checks) {}
}
