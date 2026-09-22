package com.publicmonitor.backend.domain.email.service;
import com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus;
import com.publicmonitor.backend.domain.email.*;
import com.publicmonitor.backend.domain.email.entity.*;
import com.publicmonitor.backend.domain.email.exception.*;
import com.publicmonitor.backend.domain.email.repository.EmailDeliveryRepository;
import com.publicmonitor.backend.domain.email.web.dto.*;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Lazy @Service @RequiredArgsConstructor
public class EmailDeliveryWorker {
    private final EmailDeliveryRepository deliveries;
    private final EmailSettingsService settingsService;
    private final EmailClient client;
    private final EmailProperties properties;
    private final Clock clock;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendPending(Long id) {
        var d = locked(id);
        if (d.getStatus() != EmailDeliveryState.PENDING || d.getAttemptCount() > 0) return;
        attempt(d);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EmailRecipientDeliveryResponse retry(Long id, EmailRetryRequest request) {
        var d = locked(id);
        if (d.getStatus() != EmailDeliveryState.FAILED || d.getAttemptCount() != request.expectedAttemptCount()
                || !d.getAddress().equals(request.expectedAddress())
                || d.getReport().getStatus() != MonitoringReportStatus.COMPLETED)
            throw new EmailException(EmailResponseCode.REPORT_CHANGED);
        var settings = settingsService.effective();
        if (!settings.isEnabled()) throw new EmailException(EmailResponseCode.DISABLED);
        if (!settingsService.validateTarget(settings, request.expectedAddress()).isEnabled())
            throw new EmailException(EmailResponseCode.RECIPIENT_CHANGED);
        attempt(d);
        return EmailRecipientDeliveryResponse.from(d);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUncertainFailure(Long id) {
        var d = locked(id);
        if (d.getStatus() == EmailDeliveryState.PENDING) {
            d.begin(now());
            d.fail("발송 결과를 저장하지 못했습니다. 메일함에서 수신 여부를 확인해 주세요.");
        }
    }
    private EmailDelivery locked(Long id) {
        return deliveries.findForUpdate(id).orElseThrow(() -> new EmailException(EmailResponseCode.REPORT_NOT_FOUND));
    }
    private void attempt(EmailDelivery d) {
        d.begin(now());
        if (!properties.configured()) { d.fail("서버의 메일 발신 계정을 확인해 주세요."); return; }
        var settings = settingsService.effective();
        if (!settings.isEnabled() || settings.getRecipients().stream().noneMatch(r -> r.getAddress().equals(d.getAddress()) && r.isEnabled())) {
            d.fail("발송이 꺼졌거나 수신 대상이 변경되어 전송하지 않았습니다."); return;
        }
        var report = d.getReport();
        try { client.send(d.getAddress(), report.getTitle(), report.getSummary()); d.complete(now()); }
        catch (EmailClientException e) { d.fail(e.getMessage()); }
    }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
}
