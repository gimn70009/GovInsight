package com.publicmonitor.backend.domain.telegram.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TelegramRecipient {
    @Column(name = "chat_id", nullable = false, length = 100)
    private String chatId;
    @Column(name = "recipient_name", length = 100)
    private String name;
    @Column(nullable = false)
    private boolean enabled;

    public TelegramRecipient(String chatId, String name, boolean enabled) {
        this.chatId = chatId.strip().toLowerCase(Locale.ROOT);
        this.name = name == null ? "" : name.strip();
        this.enabled = enabled;
    }
    public String getName() { return name == null ? "" : name; }
}
