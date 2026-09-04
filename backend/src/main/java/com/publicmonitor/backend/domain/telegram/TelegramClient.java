package com.publicmonitor.backend.domain.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class TelegramClient {

    private final RestClient restClient;
    private final TelegramProperties properties;

    public TelegramClient(
            @Qualifier("telegramRestClient") RestClient restClient,
            TelegramProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public long send(String text) {
        try {
            TelegramResponse response = restClient.post()
                    .uri("/bot{token}/sendMessage", properties.botToken())
                    .body(new TelegramMessageRequest(properties.chatId(), text, new LinkPreviewOptions(true)))
                    .retrieve()
                    .body(TelegramResponse.class);
            if (response == null || !response.ok() || response.result() == null) {
                throw new TelegramClientException("Telegram 응답이 올바르지 않습니다.", null);
            }
            return response.result().messageId();
        } catch (RestClientException exception) {
            throw new TelegramClientException("Telegram 보고서 전송에 실패했습니다.", exception);
        }
    }

    private record TelegramMessageRequest(
            @JsonProperty("chat_id") String chatId,
            String text,
            @JsonProperty("link_preview_options") LinkPreviewOptions linkPreviewOptions
    ) {
    }

    private record LinkPreviewOptions(@JsonProperty("is_disabled") boolean disabled) {
    }

    private record TelegramResponse(boolean ok, TelegramMessage result) {
    }

    private record TelegramMessage(@JsonProperty("message_id") long messageId) {
    }
}