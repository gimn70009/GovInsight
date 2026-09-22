package com.publicmonitor.backend.domain.email.web.dto;
import com.publicmonitor.backend.domain.email.EmailProperties;
import com.publicmonitor.backend.domain.email.entity.EmailSettings;
import java.time.LocalDateTime;
import java.util.List;
public record EmailSettingsResponse(Long version, boolean enabled, boolean configured, String provider,
        String senderAddress, String senderName, List<EmailRecipientRequest> recipients, LocalDateTime updatedAt) {
    public static EmailSettingsResponse from(EmailSettings settings, EmailProperties properties) {
        return new EmailSettingsResponse(settings.getVersion(), settings.isEnabled(), properties.configured(),
            properties.provider().name(), properties.username(), properties.senderName(),
            settings.getRecipients().stream().map(r -> new EmailRecipientRequest(r.getAddress(), r.getName(), r.isEnabled())).toList(), settings.getUpdatedAt());
    }
}
