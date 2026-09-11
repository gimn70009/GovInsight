package com.publicmonitor.backend.domain.telegram.service;
import com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus;
import com.publicmonitor.backend.domain.telegram.*;
import com.publicmonitor.backend.domain.telegram.entity.*;
import com.publicmonitor.backend.domain.telegram.exception.*;
import com.publicmonitor.backend.domain.telegram.repository.TelegramDeliveryRepository;
import com.publicmonitor.backend.domain.telegram.web.dto.*;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Lazy @Service @RequiredArgsConstructor
public class TelegramDeliveryWorker {
    private final TelegramDeliveryRepository deliveries;
    private final TelegramSettingsService settingsService;
    private final TelegramClient client;
    private final TelegramProperties properties;
    private final Clock clock;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendPending(Long id) {
        var d = locked(id);
        if (d.getStatus() != TelegramDeliveryState.PENDING || d.getAttemptCount() > 0) return;
        attempt(d);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TelegramRecipientDeliveryResponse retry(Long id, TelegramRetryRequest request) {
        var d = locked(id);
        if (d.getStatus() != TelegramDeliveryState.FAILED || d.getAttemptCount() != request.expectedAttemptCount()
                || !d.getChatId().equals(request.expectedChatId())
                || d.getReport().getStatus() != MonitoringReportStatus.COMPLETED)
            throw new TelegramException(TelegramResponseCode.REPORT_CHANGED);
        var settings = settingsService.effective();
        if (!settings.isEnabled()) throw new TelegramException(TelegramResponseCode.DISABLED);
        if (!settingsService.validateTarget(settings, request.expectedChatId()).isEnabled())
            throw new TelegramException(TelegramResponseCode.RECIPIENT_CHANGED);
        attempt(d);
        return TelegramRecipientDeliveryResponse.from(d);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUncertainFailure(Long id) {
        var d = locked(id);
        if (d.getStatus() == TelegramDeliveryState.PENDING) {
            d.begin(now());
            d.fail("발송 결과를 저장하지 못했습니다. 채팅에서 수신 여부를 확인해 주세요.");
        }
    }
    private TelegramDelivery locked(Long id) {
        return deliveries.findForUpdate(id).orElseThrow(() -> new TelegramException(TelegramResponseCode.REPORT_NOT_FOUND));
    }
    private void attempt(TelegramDelivery d) {
        d.begin(now());
        if (properties.botToken().isBlank()) { d.fail("서버의 봇 토큰을 확인해 주세요."); return; }
        var report = d.getReport();
        String message = report.getTitle() + "\n\n" + report.getSummary();
        if (message.length() > 4096) { d.fail("Telegram 메시지 최대 길이를 초과했습니다."); return; }
        try { d.complete(client.send(d.getChatId(), message), now()); }
        catch (TelegramClientException e) { d.fail(e.getMessage()); }
    }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
}
