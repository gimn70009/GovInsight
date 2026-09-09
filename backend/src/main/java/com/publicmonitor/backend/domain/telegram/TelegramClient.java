package com.publicmonitor.backend.domain.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class TelegramClient {
    private final RestClient restClient;
    private final TelegramProperties properties;

    public TelegramClient(@Qualifier("telegramRestClient") RestClient restClient, TelegramProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public long send(String chatId, String text) {
        var response = call("sendMessage", new MessageRequest(chatId, text, new LinkPreviewOptions(true)), MessageResponse.class);
        if (response == null || !response.ok() || response.result() == null) {
            throw invalidResponse();
        }
        return response.result().messageId();
    }

    public BotInfo getMe() {
        var response = call("getMe", null, BotResponse.class);
        if (response == null || !response.ok() || response.result() == null) {
            throw invalidResponse();
        }
        return response.result();
    }

    public ChatInfo getChat(String chatId) {
        var response = call("getChat", new ChatRequest(chatId), ChatResponse.class);
        if (response == null || !response.ok() || response.result() == null) {
            throw invalidResponse();
        }
        return response.result();
    }

    public WebhookInfo getWebhookInfo() {
        var response = call("getWebhookInfo", null, WebhookResponse.class);
        if (response == null || !response.ok() || response.result() == null || response.result().url() == null) {
            throw invalidResponse();
        }
        return response.result();
    }

    public List<Update> getUpdates(Long offset) {
        var response = call("getUpdates", new UpdatesRequest(offset, 100, 5, List.of("message")), UpdatesResponse.class);
        if (response == null || !response.ok() || response.result() == null) {
            throw invalidResponse();
        }
        return response.result();
    }

    private <T> T call(String method, Object body, Class<T> type) {
        try {
            var request = restClient.post().uri("/bot{token}/{method}", properties.botToken(), method);
            return (body == null ? request : request.body(body)).retrieve().body(type);
        } catch (RestClientResponseException exception) {
            String message = switch (exception.getStatusCode().value()) {
                case 400 -> "채팅 ID와 봇의 채팅 참여 여부를 확인해 주세요.";
                case 401, 404 -> "봇 인증에 실패했습니다. 서버의 봇 토큰을 확인해 주세요.";
                case 403 -> "봇이 차단됐거나 해당 채팅에 접근할 권한이 없습니다.";
                case 409 -> "다른 프로그램 또는 웹훅이 봇 메시지를 받고 있습니다. 수신 프로그램은 하나만 실행해 주세요.";
                case 429 -> "Telegram 요청이 많습니다. 잠시 후 다시 시도해 주세요.";
                default -> "Telegram 서버 응답을 확인하지 못했습니다. 수신 여부를 확인한 뒤 다시 시도해 주세요.";
            };
            // Telegram exceptions can contain the bot token in their request URI.
            throw new TelegramClientException(message, exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new TelegramClientException(
                    "Telegram 응답을 확인하지 못했습니다. 채팅에서 수신 여부를 확인한 뒤 다시 시도해 주세요.", null);
        }
    }

    private TelegramClientException invalidResponse() {
        return new TelegramClientException(
                "Telegram 응답이 올바르지 않습니다. 채팅에서 수신 여부를 확인해 주세요.", null);
    }

    public record BotInfo(@JsonProperty("first_name") String firstName, String username) {}
    public record ChatInfo(long id, String type, String title,
                           @JsonProperty("first_name") String firstName, String username) {
        public String displayName() {
            if (title != null && !title.isBlank()) return title;
            if (firstName != null && !firstName.isBlank()) return firstName;
            return username;
        }
    }
    public record WebhookInfo(String url) {}
    public record Update(@JsonProperty("update_id") long updateId, IncomingMessage message) {}
    public record IncomingMessage(long date, ChatInfo chat, Sender from, String text, List<MessageEntity> entities) {}
    public record Sender(long id, @JsonProperty("is_bot") boolean bot) {}
    public record MessageEntity(String type, int offset, int length) {}
    private record UpdatesRequest(Long offset, int limit, int timeout,
                                  @JsonProperty("allowed_updates") List<String> allowedUpdates) {}
    private record UpdatesResponse(boolean ok, List<Update> result) {}
    private record WebhookResponse(boolean ok, WebhookInfo result) {}
    private record MessageRequest(@JsonProperty("chat_id") String chatId, String text,
                                  @JsonProperty("link_preview_options") LinkPreviewOptions linkPreviewOptions) {}
    private record LinkPreviewOptions(@JsonProperty("is_disabled") boolean disabled) {}
    private record ChatRequest(@JsonProperty("chat_id") String chatId) {}
    private record MessageResponse(boolean ok, Message result) {}
    private record BotResponse(boolean ok, BotInfo result) {}
    private record ChatResponse(boolean ok, ChatInfo result) {}
    private record Message(@JsonProperty("message_id") long messageId) {}
}
