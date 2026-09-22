package com.publicmonitor.backend.domain.email.web.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record UpdateEmailSettingsRequest(
        Long version, @NotNull Boolean enabled,
        @NotNull @Size(max = 20) List<@NotNull @Valid EmailRecipientRequest> recipients
) {}
