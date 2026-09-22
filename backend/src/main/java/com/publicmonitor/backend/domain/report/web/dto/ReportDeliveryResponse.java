package com.publicmonitor.backend.domain.report.web.dto;
import com.publicmonitor.backend.domain.report.entity.*;
import com.publicmonitor.backend.domain.telegram.entity.TelegramDelivery;
import com.publicmonitor.backend.domain.telegram.web.dto.TelegramReportResponse;
import com.publicmonitor.backend.domain.email.entity.*;
import java.time.LocalDateTime;
import java.util.List;
public record ReportDeliveryResponse(Long reportId, Long runId, String title, LocalDateTime createdAt,
        LocalDateTime generatedAt, ChannelSummary telegram, ChannelSummary email) {
    public record ChannelSummary(String status, int recipientCount, int sentCount, int failedCount, String errorMessage) {}
    public static ReportDeliveryResponse from(MonitoringReport report, List<TelegramDelivery> telegram, List<EmailDelivery> email) {
        var tg = TelegramReportResponse.from(report, telegram);
        int sent = (int) email.stream().filter(d -> d.getStatus() == EmailDeliveryState.SENT).count();
        int failed = (int) email.stream().filter(d -> d.getStatus() == EmailDeliveryState.FAILED).count();
        String status = report.getStatus() == MonitoringReportStatus.FAILED ? "REPORT_FAILED"
            : report.getStatus() == MonitoringReportStatus.PENDING ? "PREPARING"
            : sent + failed < email.size() ? "SENDING" : sent > 0 && failed > 0 ? "PARTIAL"
            : failed > 0 ? "FAILED" : sent > 0 ? "SENT" : "NOT_SENT";
        return new ReportDeliveryResponse(report.getId(), report.getMonitoringRun().getId(), report.getTitle(), report.getCreatedAt(), report.getGeneratedAt(),
            new ChannelSummary(tg.status().name(), tg.recipientCount(), tg.sentCount(), tg.failedCount(), tg.errorMessage()),
            new ChannelSummary(status, email.size(), sent, failed, status.equals("REPORT_FAILED") ? "보고서를 생성하지 못했습니다." : null));
    }
}
