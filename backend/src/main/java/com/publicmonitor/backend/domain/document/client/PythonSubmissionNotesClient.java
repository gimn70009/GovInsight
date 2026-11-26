package com.publicmonitor.backend.domain.document.client;

import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClientProperties;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import static com.publicmonitor.backend.domain.document.client.SubmissionNotesContract.*;

@Component
public class PythonSubmissionNotesClient {
    private final RestClient client;
    public PythonSubmissionNotesClient(PythonMonitoringClientProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(Duration.ofSeconds(55));
        client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }
    public Response extract(Request request) {
        return client.post().uri("/internal/monitoring/submission-notes").body(request)
                .retrieve().body(Response.class);
    }
}
