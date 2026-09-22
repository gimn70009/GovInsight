package com.publicmonitor.backend.domain.email.entity;

import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "email_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailSettings extends BaseEntity {
    public static final long SETTINGS_ID = 1L;
    @Id @Column(name = "settings_id")
    private Long id = SETTINGS_ID;
    @Version private Long version;
    @Column(nullable = false)
    private boolean enabled;
    @ElementCollection
    @CollectionTable(name = "email_recipients", joinColumns = @JoinColumn(name = "settings_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_email_recipient_address", columnNames = {"settings_id", "email_address"}))
    @OrderColumn(name = "recipient_order")
    private List<EmailRecipient> recipients = new ArrayList<>();

    public static EmailSettings empty() {
        return new EmailSettings();
    }
    public void update(boolean enabled, List<EmailRecipient> recipients) {
        this.enabled = enabled;
        this.recipients.clear();
        this.recipients.addAll(recipients);
    }
}
