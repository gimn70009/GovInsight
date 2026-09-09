package com.publicmonitor.backend.domain.telegram.entity;

import com.publicmonitor.backend.domain.telegram.TelegramProperties;
import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "telegram_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TelegramSettings extends BaseEntity {
    public static final long SETTINGS_ID = 1L;
    @Id @Column(name = "settings_id")
    private Long id = SETTINGS_ID;
    @Version private Long version;
    @Column(nullable = false)
    private boolean enabled;
    @ElementCollection
    @CollectionTable(name = "telegram_recipients", joinColumns = @JoinColumn(name = "settings_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_telegram_recipient_chat", columnNames = {"settings_id", "chat_id"}))
    @OrderColumn(name = "recipient_order")
    private List<TelegramRecipient> recipients = new ArrayList<>();

    public static TelegramSettings fromDefaults(TelegramProperties properties) {
        var settings = new TelegramSettings();
        settings.enabled = properties.enabled();
        if (!properties.chatId().isBlank()) settings.recipients.add(new TelegramRecipient(properties.chatId(), properties.recipientName(), true));
        return settings;
    }
    public void update(boolean enabled, List<TelegramRecipient> recipients) {
        this.enabled = enabled;
        this.recipients.clear();
        this.recipients.addAll(recipients);
    }
}
