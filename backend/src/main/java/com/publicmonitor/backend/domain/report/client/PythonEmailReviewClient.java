package com.publicmonitor.backend.domain.report.client;

import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClientProperties;
import com.publicmonitor.backend.domain.report.EmailReviewBrief;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@lombok.extern.slf4j.Slf4j
@Component
public class PythonEmailReviewClient {
    private final RestClient client;

    public PythonEmailReviewClient(PythonMonitoringClientProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(Duration.ofSeconds(95));
        client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    public Map<Long, EmailReviewBrief.Result> summarize(List<EmailReviewBrief.Document> documents) {
        if (documents.isEmpty()) return Map.of();
        // Bound a delivery's wait even for unusually large reports; remaining cards use safe fallback copy.
        var selected = documents.stream().limit(100).toList();
        var ids = selected.stream().map(EmailReviewBrief.Document::id).collect(java.util.stream.Collectors.toSet());
        try {
            var response = client.post().uri("/internal/monitoring/email-review-briefs")
                    .body(new EmailReviewBrief.Request(selected)).retrieve().body(EmailReviewBrief.Response.class);
            if (response == null || response.documents() == null) return Map.of();
            var result = new LinkedHashMap<Long, EmailReviewBrief.Result>();
            for (var item : response.documents()) {
                if (item == null || !ids.contains(item.id())) continue;
                var clean = item.sanitized();
                if (clean.reviewReason() != null || !clean.priorityChecks().isEmpty()) result.putIfAbsent(item.id(), clean);
            }
            return result;
        } catch (RestClientException exception) {
            log.warn("Email review unavailable. error_type={}", exception.getClass().getSimpleName());
            return Map.of();
        }
    }
}
