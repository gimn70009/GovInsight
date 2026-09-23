package com.publicmonitor.backend.domain.telegram;
import com.publicmonitor.backend.domain.telegram.service.TelegramDeliveryPreparationService;
import com.publicmonitor.backend.domain.telegram.service.TelegramDeliveryWorker;
import com.publicmonitor.backend.domain.telegram.web.dto.*;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Slf4j @Lazy @Service
public class TelegramReportDeliveryService {
    private final TelegramDeliveryPreparationService preparation;
    private final TelegramDeliveryWorker worker;
    private final ExecutorService executor;
    public TelegramReportDeliveryService(TelegramDeliveryPreparationService preparation, TelegramDeliveryWorker worker,
            @Qualifier("telegramDeliveryExecutor") ExecutorService executor) {
        this.preparation = preparation; this.worker = worker; this.executor = executor;
    }
    public void deliver(Long runId) {
        var tasks = preparation.prepare(runId).stream().map(id -> CompletableFuture.runAsync(() -> {
            try { worker.sendPending(id); }
            catch (RuntimeException e) {
                log.warn("Telegram 발송 결과 처리 실패. deliveryId={}", id);
                try { worker.recordUncertainFailure(id); }
                catch (RuntimeException ignored) { log.error("Telegram 발송 상태 저장 실패. deliveryId={}", id); throw ignored; }
            }
        }, executor)).toList();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
    }
    public TelegramRecipientDeliveryResponse retry(Long deliveryId, TelegramRetryRequest request) {
        return worker.retry(deliveryId, request);
    }
}
