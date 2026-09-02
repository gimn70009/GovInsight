package com.publicmonitor.backend.domain.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TelegramClientTest {

    private MockRestServiceServer server;
    private TelegramClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org");
        server = MockRestServiceServer.bindTo(builder).build();
        TelegramProperties properties = new TelegramProperties(
                true, "test-token", "123456", Duration.ofSeconds(1), Duration.ofSeconds(1)
        );
        client = new TelegramClient(builder.build(), properties);
    }

    @Test
    void 최종_보고서를_한_메시지로_전송한다() {
        server.expect(once(), requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {
                          "chat_id": "123456",
                          "text": "[공공기관 모니터링] 9월 2일 보고서",
                          "link_preview_options": {"is_disabled": true}
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "result": {"message_id": 777}
                        }
                        """, MediaType.APPLICATION_JSON));

        long messageId = client.send("[공공기관 모니터링] 9월 2일 보고서");

        assertThat(messageId).isEqualTo(777L);
        server.verify();
    }
}