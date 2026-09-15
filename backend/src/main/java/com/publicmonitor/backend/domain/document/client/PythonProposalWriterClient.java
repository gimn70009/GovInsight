package com.publicmonitor.backend.domain.document.client;

import com.publicmonitor.backend.domain.document.client.dto.PythonProposalWriteRequest;
import com.publicmonitor.backend.domain.document.web.dto.ProposalWriteResponse;
import com.publicmonitor.backend.domain.document.web.dto.ProposalTemplateResponse;
import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClientProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@org.springframework.context.annotation.Lazy
public class PythonProposalWriterClient {
    private static final Logger log = LoggerFactory.getLogger(PythonProposalWriterClient.class);
    private final RestClient client;

    public PythonProposalWriterClient(PythonMonitoringClientProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(Duration.ofSeconds(190));
        client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    public ProposalTemplateResponse inspect(PythonProposalWriteRequest request) {
        try {
            // The LOB transaction has ended before this network request begins.
            var response = client.post().uri("/internal/monitoring/proposal-template")
                    .body(java.util.Map.of("fileName", request.fileName(), "templateText", request.templateText()))
                    .retrieve().body(ProposalTemplateResponse.class);
            if (response == null || response.status() == null || response.sectionTitles() == null
                    || !java.util.Set.of("WRITABLE", "NOT_WRITABLE", "UNAVAILABLE").contains(response.status())
                    || ("WRITABLE".equals(response.status()) && response.sectionTitles().isEmpty())) {
                return ProposalTemplateResponse.unavailable();
            }
            return response;
        } catch (RestClientException exception) {
            log.warn("제안 양식 확인 실패: {}", exception.getClass().getSimpleName());
            return ProposalTemplateResponse.unavailable();
        }
    }

    public ProposalWriteResponse write(PythonProposalWriteRequest request) {
        try {
            var response = client.post().uri("/internal/monitoring/proposal-write")
                    .body(request).retrieve().body(ProposalWriteResponse.class);
            return response == null || response.sections() == null
                    ? ProposalWriteResponse.unavailable() : response;
        } catch (RestClientException exception) {
            log.warn("제안 초안 AI 연동 실패: {}", exception.getClass().getSimpleName());
            return ProposalWriteResponse.unavailable();
        }
    }
}
