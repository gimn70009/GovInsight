package com.publicmonitor.backend.domain.document.client;

import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClientProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ParticipationRewriteStreamingTest {
    @Test void deliversProgressBeforeTerminalAndKeepsItWhenStreamEndsEarly() throws Exception {
        var request = new Request(List.of(new Source("s0", "중소기업만 신청", "", List.of("COMPANY_SIZE"))), List.of(), List.of());
        var result = new Response(List.of(new Statement(List.of("s0"), "COMPANY_SIZE",
                "신청기업은 중소기업이어야 합니다.", "", List.of("중소기업만 신청"), "ELIGIBILITY")), List.of(), "READY");
        var delivered = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/monitoring/participation-rewrite/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            var bytes = (JsonMapper.builder().build().writeValueAsString(new PythonParticipationRewriteClient.Progress(result, false)) + "\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseBody().write(bytes); exchange.getResponseBody().flush();
            try { assertThat(delivered.await(3, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new PythonParticipationRewriteClient(new PythonMonitoringClientProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), Duration.ofSeconds(2)));
            var snapshots = new ArrayList<Response>();
            var finalResult = client.rewrite(request, partial -> { snapshots.add(partial); delivered.countDown(); });
            assertThat(snapshots).containsExactly(result);
            assertThat(finalResult).isEqualTo(result);
        } finally { server.stop(0); }
    }
}
