package com.publicmonitor.backend.domain.telegram.web.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record UpdateTelegramSettingsRequest(
        Long version, @NotNull Boolean enabled,
        @NotNull @Size(max = 20) List<@NotNull @Valid TelegramRecipientRequest> recipients
) {}
