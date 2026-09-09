package com.publicmonitor.backend.domain.telegram.web.dto;
import com.publicmonitor.backend.domain.telegram.entity.TelegramDelivery;
import com.publicmonitor.backend.domain.telegram.entity.TelegramDeliveryState;
import java.time.LocalDateTime;
public record TelegramRecipientDeliveryResponse(
        Long deliveryId, String chatId, String name, TelegramDeliveryState status,
        int attemptCount, LocalDateTime attemptedAt, LocalDateTime sentAt, String errorMessage
) {
    public static TelegramRecipientDeliveryResponse from(TelegramDelivery d) {
        return new TelegramRecipientDeliveryResponse(d.getId(), d.getChatId(), d.getRecipientName(), d.getStatus(),
                d.getAttemptCount(), d.getAttemptedAt(), d.getSentAt(), d.getErrorMessage());
    }
}
