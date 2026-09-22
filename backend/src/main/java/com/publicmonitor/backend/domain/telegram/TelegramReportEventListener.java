package com.publicmonitor.backend.domain.telegram;

import com.publicmonitor.backend.domain.report.event.ReportCompletedEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@lombok.extern.slf4j.Slf4j
@Component
public class TelegramReportEventListener {

    private final TelegramReportDeliveryService deliveryService;

    public TelegramReportEventListener(@Lazy TelegramReportDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliver(ReportCompletedEvent event) {
        try { deliveryService.deliver(event.runId()); }
        catch (RuntimeException exception) { log.error("Telegram 보고서 발송 준비 실패. runId={}, errorType={}", event.runId(), exception.getClass().getSimpleName()); }
    }
}