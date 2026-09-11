package com.publicmonitor.backend.domain.telegram.service;
import com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import com.publicmonitor.backend.domain.telegram.entity.*;
import com.publicmonitor.backend.domain.telegram.exception.*;
import com.publicmonitor.backend.domain.telegram.repository.TelegramDeliveryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Lazy @Service @RequiredArgsConstructor
public class TelegramDeliveryPreparationService {
    private final MonitoringReportRepository reports;
    private final TelegramDeliveryRepository deliveries;
    private final TelegramSettingsService settingsService;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> prepare(Long runId) {
        var report = reports.findForTelegramByRunId(runId)
                .orElseThrow(() -> new TelegramException(TelegramResponseCode.REPORT_NOT_FOUND));
        if (report.getStatus() != MonitoringReportStatus.COMPLETED || report.isTelegramSent()) return List.of();
        var existing = deliveries.findByReportIdOrderById(report.getId());
        if (!existing.isEmpty()) return existing.stream().filter(d -> d.getStatus() == TelegramDeliveryState.PENDING)
                .map(TelegramDelivery::getId).toList();
        // A repeated completion event must not retry migrated legacy failures.
        if (report.getTelegramErrorMessage() != null || report.getTelegramAttemptCount() > 0) return List.of();
        var settings = settingsService.effective();
        if (!settings.isEnabled()) return List.of();
        return deliveries.saveAllAndFlush(settings.getRecipients().stream().filter(TelegramRecipient::isEnabled)
                .map(r -> TelegramDelivery.pending(report, r)).toList()).stream().map(TelegramDelivery::getId).toList();
    }
}
