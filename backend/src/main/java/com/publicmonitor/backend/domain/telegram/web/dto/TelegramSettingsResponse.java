package com.publicmonitor.backend.domain.telegram.web.dto;
import com.publicmonitor.backend.domain.telegram.entity.TelegramSettings;
import java.time.LocalDateTime;
import java.util.List;
public record TelegramSettingsResponse(
        Long version, boolean enabled, boolean botConfigured,
        List<TelegramRecipientRequest> recipients, LocalDateTime updatedAt
) {
    public static TelegramSettingsResponse from(TelegramSettings settings, boolean configured) {
        return new TelegramSettingsResponse(settings.getVersion(), settings.isEnabled(), configured,
                settings.getRecipients().stream().map(r -> new TelegramRecipientRequest(r.getChatId(), r.getName(), r.isEnabled())).toList(),
                settings.getUpdatedAt());
    }
}
