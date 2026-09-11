package com.publicmonitor.backend.domain.telegram.web.dto;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus;
import com.publicmonitor.backend.domain.telegram.entity.TelegramDelivery;
import com.publicmonitor.backend.domain.telegram.entity.TelegramDeliveryState;
import java.time.LocalDateTime;
import java.util.List;

public record TelegramReportResponse(
        Long reportId, Long runId, String title, MonitoringTriggerType triggerType,
        LocalDateTime createdAt, LocalDateTime generatedAt, TelegramDeliveryStatus status,
        int recipientCount, int sentCount, int failedCount, String errorMessage
) {
    public static TelegramReportResponse from(MonitoringReport r, List<TelegramDelivery> deliveries) {
        int sent = (int) deliveries.stream().filter(d -> d.getStatus() == TelegramDeliveryState.SENT).count();
        int failed = (int) deliveries.stream().filter(d -> d.getStatus() == TelegramDeliveryState.FAILED).count();
        TelegramDeliveryStatus status;
        if (r.getStatus() == MonitoringReportStatus.FAILED) status = TelegramDeliveryStatus.REPORT_FAILED;
        else if (r.getStatus() == MonitoringReportStatus.PENDING) status = TelegramDeliveryStatus.PREPARING;
        else if (sent + failed < deliveries.size()) status = TelegramDeliveryStatus.SENDING;
        else if (sent > 0 && failed > 0) status = TelegramDeliveryStatus.PARTIAL;
        else if (failed > 0) status = TelegramDeliveryStatus.FAILED;
        else if (sent > 0 || r.isTelegramSent()) status = TelegramDeliveryStatus.SENT;
        else if (r.getTelegramErrorMessage() != null) status = TelegramDeliveryStatus.FAILED;
        else status = TelegramDeliveryStatus.NOT_SENT;
        String error = status == TelegramDeliveryStatus.REPORT_FAILED ? "보고서를 생성하지 못했습니다."
                : deliveries.isEmpty() ? r.getTelegramErrorMessage() : null;
        return new TelegramReportResponse(r.getId(), r.getMonitoringRun().getId(), r.getTitle(),
                r.getMonitoringRun().getTriggerType(), r.getCreatedAt(), r.getGeneratedAt(), status,
                deliveries.size(), sent, failed, error);
    }
}
