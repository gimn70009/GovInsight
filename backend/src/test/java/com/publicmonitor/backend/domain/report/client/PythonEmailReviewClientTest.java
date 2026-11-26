package com.publicmonitor.backend.domain.report.client;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClientProperties;
import com.publicmonitor.backend.domain.report.EmailReviewBrief;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PythonEmailReviewClientTest {
    @Test
    void validatesContractAndRejectsUnknownOrMalformedResults() throws Exception {
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/monitoring/email-review-briefs", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var body = new EmailReviewBrief.Response(List.of(
                    new EmailReviewBrief.Result(1, "제조 기술과 연결됩니다.", List.of("필수 참여기관을 확인하세요.", "연구개발기관 자격 — 기관 유형을 확인하세요.")),
                    new EmailReviewBrief.Result(2, "다른 공고입니다.", List.of()),
                    new EmailReviewBrief.Result(3, "<script>invalid</script>", List.of())));
            byte[] bytes = JsonMapper.builder().build().writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = client(server);
            var sources = List.of(new EmailReviewBrief.Source("company_fit", "제조 기술과 연결됩니다."));
            var result = client.summarize(List.of(new EmailReviewBrief.Document(1, sources),
                    new EmailReviewBrief.Document(3, sources)));
            assertThat(result).containsOnlyKeys(1L);
            assertThat(result.get(1L).reviewReason()).isEqualTo("제조 기술과 연결됩니다.");
            assertThat(result.get(1L).priorityChecks()).containsExactly("필수 참여기관을 확인하세요.");
            assertThat(request.get()).contains("company_fit", "sources").doesNotContain("deadline", "sentAt");
        } finally { server.stop(0); }
    }

    @Test
    void unavailableProviderDoesNotFailMailRendering() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/monitoring/email-review-briefs", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            assertThat(client(server).summarize(List.of(new EmailReviewBrief.Document(1,
                    List.of(new EmailReviewBrief.Source("company_fit", "저장 분석")))))).isEmpty();
            assertThat(client(server).summarize(List.of())).isEmpty();
        } finally { server.stop(0); }
    }

    private PythonEmailReviewClient client(HttpServer server) {
        return new PythonEmailReviewClient(new PythonMonitoringClientProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }
}
