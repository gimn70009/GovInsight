package com.publicmonitor.backend.domain.telegram.web.dto;
import com.publicmonitor.backend.global.presentation.YearNotation;
import java.util.List;
public record TelegramReportDetailResponse(
        TelegramReportResponse report, String body, List<TelegramRecipientDeliveryResponse> deliveries
) {
    public TelegramReportDetailResponse {
        body = YearNotation.display(body);
    }
}
