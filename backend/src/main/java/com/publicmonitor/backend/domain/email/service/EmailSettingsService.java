package com.publicmonitor.backend.domain.email.service;

import com.publicmonitor.backend.domain.email.EmailProperties;
import com.publicmonitor.backend.domain.email.entity.EmailSettings;
import com.publicmonitor.backend.domain.email.exception.EmailException;
import com.publicmonitor.backend.domain.email.exception.EmailResponseCode;
import com.publicmonitor.backend.domain.email.repository.EmailSettingsRepository;
import com.publicmonitor.backend.domain.email.web.dto.EmailSettingsResponse;
import com.publicmonitor.backend.domain.email.web.dto.UpdateEmailSettingsRequest;
import java.util.Objects;
import java.util.HashSet;
import com.publicmonitor.backend.domain.email.entity.EmailRecipient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Lazy
@Service
@RequiredArgsConstructor
public class EmailSettingsService {
    private final EmailSettingsRepository repository;
    private final EmailProperties properties;

    @Transactional(readOnly = true)
    public EmailSettingsResponse find() {
        return EmailSettingsResponse.from(effective(), properties);
    }

    @Transactional(readOnly = true)
    public EmailSettings effective() {
        var settings = repository.findById(EmailSettings.SETTINGS_ID)
                .orElseGet(EmailSettings::empty);
        settings.getRecipients().size();
        return settings;
    }

    @Transactional
    public EmailSettingsResponse update(UpdateEmailSettingsRequest request) {
        EmailSettings settings = effective();
        if (!Objects.equals(settings.getVersion(), request.version())) {
            throw new EmailException(EmailResponseCode.SETTINGS_CHANGED);
        }
        var recipients = request.recipients().stream()
                .map(r -> new EmailRecipient(r.address(), r.name(), r.enabled())).toList();
        var unique = new HashSet<String>();
        if (recipients.stream().anyMatch(r -> !unique.add(r.getAddress()))) {
            throw new EmailException(EmailResponseCode.DUPLICATE_RECIPIENT);
        }
        if (request.enabled() && !properties.configured()) {
            throw new EmailException(EmailResponseCode.NOT_CONFIGURED);
        }
        try {
            // Oracle checks unique addresses during each indexed-list UPDATE. Clear and flush
            // within this transaction before replacing the list so swaps/deletions cannot collide.
            if (settings.getVersion() != null && !settings.getRecipients().isEmpty()) {
                settings.update(settings.isEnabled(), java.util.List.of());
                repository.flush();
            }
            settings.update(request.enabled(), recipients);
            repository.saveAndFlush(settings);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw new EmailException(EmailResponseCode.SETTINGS_CHANGED);
        }
        return EmailSettingsResponse.from(settings, properties);
    }

    public EmailRecipient validateTarget(EmailSettings settings, String expectedAddress) {
        if (!properties.configured()) throw new EmailException(EmailResponseCode.NOT_CONFIGURED);
        return settings.getRecipients().stream().filter(r -> r.getAddress().equals(expectedAddress)).findFirst()
                .orElseThrow(() -> new EmailException(EmailResponseCode.RECIPIENT_CHANGED));
    }
}
