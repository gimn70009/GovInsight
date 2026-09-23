package com.publicmonitor.backend.domain.report.web.dto;
import com.publicmonitor.backend.domain.email.web.dto.EmailRecipientDeliveryResponse;
import com.publicmonitor.backend.domain.telegram.web.dto.TelegramRecipientDeliveryResponse;
import com.publicmonitor.backend.global.presentation.YearNotation;
import java.util.List;
public record ReportDeliveryDetailResponse(ReportDeliveryResponse report, String body,
    List<TelegramRecipientDeliveryResponse> telegramDeliveries, List<EmailRecipientDeliveryResponse> emailDeliveries) {
    public ReportDeliveryDetailResponse {
        body = YearNotation.display(body);
    }
}
