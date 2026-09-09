package com.publicmonitor.backend.domain.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                true, "test-token", "123456", Duration.ofSeconds(1), Duration.ofSeconds(1), ""
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

        long messageId = client.send("123456", "[공공기관 모니터링] 9월 2일 보고서");

        assertThat(messageId).isEqualTo(777L);
        server.verify();
    }

    @Test
    void 봇과_채팅_확인은_메시지를_발송하지_않는다() {
        server.expect(requestTo("https://api.telegram.org/bottest-token/getMe"))
                .andRespond(withSuccess("""
                        {"ok":true,"result":{"first_name":"GovInsight","username":"test_bot"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.telegram.org/bottest-token/getChat"))
                .andExpect(content().json("""
                        {"chat_id":"-100123"}
                        """))
                .andRespond(withSuccess("""
                        {"ok":true,"result":{"id":-100123,"type":"supergroup","title":"사업개발팀"}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.getMe().username()).isEqualTo("test_bot");
        assertThat(client.getChat("-100123").displayName()).isEqualTo("사업개발팀");
        server.verify();
    }

    @Test
    void 접근_거부의_원문과_토큰을_오류에_노출하지_않는다() {
        server.expect(requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                        .body("unsafe body test-token"));
        assertThatThrownBy(() -> client.send("123456", "test"))
                .isInstanceOf(TelegramClientException.class)
                .hasMessage("봇이 차단됐거나 해당 채팅에 접근할 권한이 없습니다.")
                .hasNoCause();
        server.verify();
    }

    @Test
    void 응답_시간초과는_수신_확인을_안내하며_자동_재시도하지_않는다() {
        server.expect(once(), requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("test-token"); });
        assertThatThrownBy(() -> client.send("123456", "test"))
                .isInstanceOf(TelegramClientException.class)
                .hasMessageContaining("수신 여부")
                .hasNoCause();
        server.verify();
    }
    @Test
    void 업데이트를_긴_폴링으로_받고_숫자_ID와_명령_정보를_읽는다() {
        server.expect(requestTo("https://api.telegram.org/bottest-token/getUpdates"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"offset":123,"limit":100,"timeout":5,"allowed_updates":["message"]}
                        """))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[{"update_id":123,"message":{"message_id":1,"date":1788900000,
                        "chat":{"id":9123456789,"type":"private","first_name":"Test"},
                        "from":{"id":9123456789,"is_bot":false,"first_name":"Test"},"text":"/start",
                        "entities":[{"type":"bot_command","offset":0,"length":6}]}},
                        {"update_id":124,"edited_message":{"text":"/start"}}]}
                        """, MediaType.APPLICATION_JSON));
        var updates = client.getUpdates(123L);
        assertThat(updates).hasSize(2);
        assertThat(updates.getFirst().message().chat().id()).isEqualTo(9_123_456_789L);
        assertThat(updates.getFirst().message().entities().getFirst().type()).isEqualTo("bot_command");
        assertThat(updates.get(1).message()).isNull();
        server.verify();
    }

    @Test
    void 웹훅_상태는_조회만_한다() {
        server.expect(requestTo("https://api.telegram.org/bottest-token/getWebhookInfo"))
                .andRespond(withSuccess("""
                        {"ok":true,"result":{"url":"","pending_update_count":2}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.getWebhookInfo().url()).isEmpty();
        server.verify();
    }

    @Test
    void 수신_충돌_상태를_보존하되_토큰과_원문은_제거한다() {
        server.expect(requestTo("https://api.telegram.org/bottest-token/getUpdates"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.CONFLICT).body("unsafe test-token"));
        try {
            client.getUpdates(null);
            org.junit.jupiter.api.Assertions.fail("Expected conflict");
        } catch (TelegramClientException exception) {
            assertThat(exception.statusCode()).isEqualTo(409);
            assertThat(exception.getMessage()).doesNotContain("test-token").contains("하나만");
            assertThat(exception.getCause()).isNull();
        }
        server.verify();
    }
}
