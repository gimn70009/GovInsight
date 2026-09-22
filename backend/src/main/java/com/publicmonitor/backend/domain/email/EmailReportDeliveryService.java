package com.publicmonitor.backend.domain.email;
import com.publicmonitor.backend.domain.email.service.EmailDeliveryPreparationService;
import com.publicmonitor.backend.domain.email.service.EmailDeliveryWorker;
import com.publicmonitor.backend.domain.email.web.dto.*;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Slf4j @Lazy @Service
public class EmailReportDeliveryService {
    private final EmailDeliveryPreparationService preparation;
    private final EmailDeliveryWorker worker;
    private final ExecutorService executor;
    public EmailReportDeliveryService(EmailDeliveryPreparationService preparation, EmailDeliveryWorker worker,
            @Qualifier("emailDeliveryExecutor") ExecutorService executor) {
        this.preparation = preparation; this.worker = worker; this.executor = executor;
    }
    public void deliver(Long runId) {
        var tasks = preparation.prepare(runId).stream().map(id -> CompletableFuture.runAsync(() -> {
            try { worker.sendPending(id); }
            catch (RuntimeException e) {
                log.warn("Email 발송 결과 처리 실패. deliveryId={}", id);
                try { worker.recordUncertainFailure(id); }
                catch (RuntimeException ignored) { log.error("Email 발송 상태 저장 실패. deliveryId={}", id); }
            }
        }, executor)).toList();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
    }
    public EmailRecipientDeliveryResponse retry(Long deliveryId, EmailRetryRequest request) {
        return worker.retry(deliveryId, request);
    }
}
