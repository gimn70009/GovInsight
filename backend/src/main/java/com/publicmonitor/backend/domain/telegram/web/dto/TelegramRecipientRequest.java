package com.publicmonitor.backend.domain.telegram.web.dto;
import jakarta.validation.constraints.*;
public record TelegramRecipientRequest(
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "^(-?[1-9][0-9]{0,19}|@[A-Za-z][A-Za-z0-9_]{4,31})$",
                message = "숫자 채팅 ID 또는 @채널이름을 입력해 주세요.") String chatId,
        @NotNull @Size(max = 100) String name,
        @NotNull Boolean enabled
) {}
