package com.publicmonitor.backend.domain.document.client;

import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClientProperties;
import java.time.Duration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

@lombok.extern.slf4j.Slf4j
@Component
public class PythonParticipationRewriteClient {
    private final RestClient client;
    public PythonParticipationRewriteClient(PythonMonitoringClientProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(Duration.ofSeconds(620));
        client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }
    public Response rewrite(Request request, Consumer<Response> progress) {
        Response[] latest = {Response.unavailable(request)};
        try {
            return client.post().uri("/internal/monitoring/participation-rewrite/stream")
                    .body(request).exchange((httpRequest, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) return latest[0];
                        var mapper = JsonMapper.builder().build();
                        long deadline = System.nanoTime() + Duration.ofSeconds(620).toNanos();
                        try (var reader = new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null && System.nanoTime() < deadline) {
                                if (line.isBlank()) continue;
                                var event = mapper.readValue(line, Progress.class);
                                if (event == null) continue;
                                var result = event.result();
                                if (!valid(result)) continue;
                                // An interrupted stream or terminal provider failure cannot erase verified progress.
                                if ("READY".equals(result.status()) || latest[0].statements().isEmpty()) latest[0] = result;
                                if (event.done()) return latest[0];
                                progress.accept(latest[0]);
                            }
                        }
                        return latest[0];
                    });
        } catch (RestClientException | tools.jackson.core.JacksonException exception) {
            log.warn("Participation rewrite request failed. error_type={}", exception.getClass().getSimpleName());
            return latest[0];
        }
    }
    private static boolean valid(Response result) {
        return result != null && result.statements() != null && result.evidenceOnlyIds() != null
                && ("READY".equals(result.status()) || "UNAVAILABLE".equals(result.status()));
    }
    public record Progress(Response result, boolean done) {}
}
