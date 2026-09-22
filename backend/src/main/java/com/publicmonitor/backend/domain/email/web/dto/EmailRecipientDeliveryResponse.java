package com.publicmonitor.backend.domain.email.web.dto;
import com.publicmonitor.backend.domain.email.entity.EmailDelivery;
import com.publicmonitor.backend.domain.email.entity.EmailDeliveryState;
import java.time.LocalDateTime;
public record EmailRecipientDeliveryResponse(
        Long deliveryId, String address, String name, EmailDeliveryState status,
        int attemptCount, LocalDateTime attemptedAt, LocalDateTime sentAt, String errorMessage
) {
    public static EmailRecipientDeliveryResponse from(EmailDelivery d) {
        return new EmailRecipientDeliveryResponse(d.getId(), d.getAddress(), d.getRecipientName(), d.getStatus(),
                d.getAttemptCount(), d.getAttemptedAt(), d.getSentAt(), d.getErrorMessage());
    }
}
