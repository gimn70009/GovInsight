package com.publicmonitor.backend.domain.email.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailRecipient {
    @Column(name = "email_address", nullable = false, length = 254)
    private String address;
    @Column(name = "recipient_name", length = 100)
    private String name;
    @Column(nullable = false)
    private boolean enabled;

    public EmailRecipient(String address, String name, boolean enabled) {
        this.address = address.strip().toLowerCase(Locale.ROOT);
        this.name = name == null ? "" : name.strip();
        this.enabled = enabled;
    }
    public String getName() { return name == null ? "" : name; }
}
