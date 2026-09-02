package com.publicmonitor.backend.domain.telegram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String botToken,
        @DefaultValue("") String chatId,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout
) {
    public boolean isConfigured() {
        return enabled && !botToken.isBlank() && !chatId.isBlank();
    }
}