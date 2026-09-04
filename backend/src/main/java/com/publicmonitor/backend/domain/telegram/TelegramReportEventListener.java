package com.publicmonitor.backend.domain.telegram;

import com.publicmonitor.backend.domain.report.event.ReportCompletedEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TelegramReportEventListener {

    private final TelegramReportDeliveryService deliveryService;

    public TelegramReportEventListener(@Lazy TelegramReportDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliver(ReportCompletedEvent event) {
        deliveryService.deliver(event.runId());
    }
}