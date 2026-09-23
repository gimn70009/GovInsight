package com.publicmonitor.backend.domain.email;
import com.publicmonitor.backend.domain.report.event.ReportCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
@Slf4j @Component
public class EmailReportEventListener {
    private final EmailReportDeliveryService delivery;
    public EmailReportEventListener(@Lazy EmailReportDeliveryService delivery) { this.delivery = delivery; }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliver(ReportCompletedEvent event) {
        try { delivery.deliver(event.runId()); }
        catch (RuntimeException exception) { log.error("이메일 보고서 발송 준비 실패. runId={}, errorType={}", event.runId(), exception.getClass().getSimpleName()); }
    }
}
