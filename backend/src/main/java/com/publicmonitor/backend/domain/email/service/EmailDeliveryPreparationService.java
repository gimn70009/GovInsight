package com.publicmonitor.backend.domain.email.service;
import com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import com.publicmonitor.backend.domain.email.entity.*;
import com.publicmonitor.backend.domain.email.exception.*;
import com.publicmonitor.backend.domain.email.repository.EmailDeliveryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Lazy @Service @RequiredArgsConstructor
public class EmailDeliveryPreparationService {
    private final MonitoringReportRepository reports;
    private final EmailDeliveryRepository deliveries;
    private final EmailSettingsService settingsService;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> prepare(Long runId) {
        var report = reports.findForTelegramByRunId(runId)
                .orElseThrow(() -> new EmailException(EmailResponseCode.REPORT_NOT_FOUND));
        if (report.getStatus() != MonitoringReportStatus.COMPLETED) return List.of();
        var existing = deliveries.findByReportIdOrderById(report.getId());
        if (!existing.isEmpty()) return existing.stream().filter(d -> d.getStatus() == EmailDeliveryState.PENDING)
                .map(EmailDelivery::getId).toList();

        var settings = settingsService.effective();
        if (!settings.isEnabled()) return List.of();
        return deliveries.saveAllAndFlush(settings.getRecipients().stream().filter(EmailRecipient::isEnabled)
                .map(r -> EmailDelivery.pending(report, r)).toList()).stream().map(EmailDelivery::getId).toList();
    }
}
