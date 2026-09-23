package com.publicmonitor.backend.domain.report.service;

import org.springframework.context.annotation.Lazy;
import com.publicmonitor.backend.domain.email.repository.EmailDeliveryRepository;
import com.publicmonitor.backend.domain.email.entity.EmailDeliveryState;
import com.publicmonitor.backend.domain.telegram.repository.TelegramDeliveryRepository;
import com.publicmonitor.backend.domain.telegram.entity.TelegramDeliveryState;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Lazy @Service @RequiredArgsConstructor
public class ReportDeliveryAttemptService {
    private final EmailDeliveryRepository email;
    private final TelegramDeliveryRepository telegram;
    private final Clock clock;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimEmail(Long id) {
        var d = email.findForUpdate(id).orElseThrow();
        if (d.getStatus() != EmailDeliveryState.PENDING) return false;
        if (d.getAttemptCount() > 0) {
            if (d.getAttemptedAt() != null && d.getAttemptedAt().isBefore(now().minusMinutes(2)))
                d.fail("이전 발송 결과가 불확실합니다. 수신 여부 확인 후 재전송하세요.");
            return false;
        }
        d.begin(now());
        return true;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimTelegram(Long id) {
        var d = telegram.findForUpdate(id).orElseThrow();
        if (d.getStatus() != TelegramDeliveryState.PENDING) return false;
        if (d.getAttemptCount() > 0) {
            if (d.getAttemptedAt() != null && d.getAttemptedAt().isBefore(now().minusMinutes(2)))
                d.fail("이전 발송 결과가 불확실합니다. 수신 여부 확인 후 재전송하세요.");
            return false;
        }
        d.begin(now());
        return true;
    }
    @Transactional(readOnly = true)
    public boolean hasPending(Long runId) {
        return email.existsByReportMonitoringRunIdAndStatus(runId, EmailDeliveryState.PENDING)
                || telegram.existsByReportMonitoringRunIdAndStatus(runId, TelegramDeliveryState.PENDING);
    }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
}
