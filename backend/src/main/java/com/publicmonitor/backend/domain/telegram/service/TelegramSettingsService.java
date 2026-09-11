package com.publicmonitor.backend.domain.telegram.service;

import com.publicmonitor.backend.domain.telegram.TelegramProperties;
import com.publicmonitor.backend.domain.telegram.entity.TelegramSettings;
import com.publicmonitor.backend.domain.telegram.exception.TelegramException;
import com.publicmonitor.backend.domain.telegram.exception.TelegramResponseCode;
import com.publicmonitor.backend.domain.telegram.repository.TelegramSettingsRepository;
import com.publicmonitor.backend.domain.telegram.web.dto.TelegramSettingsResponse;
import com.publicmonitor.backend.domain.telegram.web.dto.UpdateTelegramSettingsRequest;
import java.util.Objects;
import java.util.HashSet;
import com.publicmonitor.backend.domain.telegram.entity.TelegramRecipient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Lazy
@Service
@RequiredArgsConstructor
public class TelegramSettingsService {
    private final TelegramSettingsRepository repository;
    private final TelegramProperties properties;

    @Transactional(readOnly = true)
    public TelegramSettingsResponse find() {
        return TelegramSettingsResponse.from(effective(), !properties.botToken().isBlank());
    }

    @Transactional(readOnly = true)
    public TelegramSettings effective() {
        var settings = repository.findById(TelegramSettings.SETTINGS_ID)
                .orElseGet(() -> TelegramSettings.fromDefaults(properties));
        settings.getRecipients().size();
        return settings;
    }

    @Transactional
    public TelegramSettingsResponse update(UpdateTelegramSettingsRequest request) {
        TelegramSettings settings = effective();
        if (!Objects.equals(settings.getVersion(), request.version())) {
            throw new TelegramException(TelegramResponseCode.SETTINGS_CHANGED);
        }
        var recipients = request.recipients().stream()
                .map(r -> new TelegramRecipient(r.chatId(), r.name(), r.enabled())).toList();
        var unique = new HashSet<String>();
        if (recipients.stream().anyMatch(r -> !unique.add(r.getChatId()))) {
            throw new TelegramException(TelegramResponseCode.DUPLICATE_RECIPIENT);
        }
        if (request.enabled() && properties.botToken().isBlank()) {
            throw new TelegramException(TelegramResponseCode.NOT_CONFIGURED);
        }
        try {
            // Oracle checks unique chat IDs during each indexed-list UPDATE. Clear and flush
            // within this transaction before replacing the list so swaps/deletions cannot collide.
            if (settings.getVersion() != null && !settings.getRecipients().isEmpty()) {
                settings.update(settings.isEnabled(), java.util.List.of());
                repository.flush();
            }
            settings.update(request.enabled(), recipients);
            repository.saveAndFlush(settings);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw new TelegramException(TelegramResponseCode.SETTINGS_CHANGED);
        }
        return TelegramSettingsResponse.from(settings, !properties.botToken().isBlank());
    }

    public TelegramRecipient validateTarget(TelegramSettings settings, String expectedChatId) {
        if (properties.botToken().isBlank()) throw new TelegramException(TelegramResponseCode.NOT_CONFIGURED);
        return settings.getRecipients().stream().filter(r -> r.getChatId().equals(expectedChatId)).findFirst()
                .orElseThrow(() -> new TelegramException(TelegramResponseCode.RECIPIENT_CHANGED));
    }
}
