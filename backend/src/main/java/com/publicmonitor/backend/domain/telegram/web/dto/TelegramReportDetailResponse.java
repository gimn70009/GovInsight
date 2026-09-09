package com.publicmonitor.backend.domain.telegram.web.dto;
import java.util.List;
public record TelegramReportDetailResponse(
        TelegramReportResponse report, String body, List<TelegramRecipientDeliveryResponse> deliveries
) {}
