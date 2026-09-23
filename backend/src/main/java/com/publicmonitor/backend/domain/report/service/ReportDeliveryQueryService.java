package com.publicmonitor.backend.domain.report.service;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import com.publicmonitor.backend.domain.report.web.dto.*;
import com.publicmonitor.backend.domain.telegram.repository.TelegramDeliveryRepository;
import com.publicmonitor.backend.domain.telegram.web.dto.TelegramRecipientDeliveryResponse;
import com.publicmonitor.backend.domain.email.repository.EmailDeliveryRepository;
import com.publicmonitor.backend.domain.email.web.dto.EmailRecipientDeliveryResponse;
import com.publicmonitor.backend.domain.email.exception.*;
import com.publicmonitor.backend.global.response.PageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Lazy @Service @RequiredArgsConstructor
public class ReportDeliveryQueryService {
    private final MonitoringReportRepository reports;
    private final TelegramDeliveryRepository telegram;
    private final EmailDeliveryRepository email;
    @Transactional(readOnly = true)
    public PageResponse<ReportDeliveryResponse> list(int page, int size, LocalDate from, LocalDate to, String channel, String status) {
        if (from != null && to != null && from.isAfter(to)) throw new EmailException(EmailResponseCode.INVALID_DATE_RANGE);
        var result = reports.findDeliveryHistory(from == null ? null : from.atStartOfDay(), to == null ? null : to.plusDays(1).atStartOfDay(), channel, status, PageRequest.of(page, size));
        if (result.isEmpty()) return PageResponse.from(result.map(r -> ReportDeliveryResponse.from(r, List.of(), List.of())));
        var ids = result.map(r -> r.getId()).getContent();
        var tg = telegram.findByReportIdInOrderById(ids).stream().collect(Collectors.groupingBy(d -> d.getReport().getId()));
        var mail = email.findByReportIdInOrderById(ids).stream().collect(Collectors.groupingBy(d -> d.getReport().getId()));
        return PageResponse.from(result.map(r -> ReportDeliveryResponse.from(r, tg.getOrDefault(r.getId(), List.of()), mail.getOrDefault(r.getId(), List.of()))));
    }
    @Transactional(readOnly = true)
    public ReportDeliveryDetailResponse detail(Long id) {
        var report = reports.findTelegramDetail(id).orElseThrow(() -> new EmailException(EmailResponseCode.REPORT_NOT_FOUND));
        var tg = telegram.findByReportIdOrderById(id); var mail = email.findByReportIdOrderById(id);
        return new ReportDeliveryDetailResponse(ReportDeliveryResponse.from(report, tg, mail), report.getSummary(),
            tg.stream().map(TelegramRecipientDeliveryResponse::from).toList(), mail.stream().map(EmailRecipientDeliveryResponse::from).toList());
    }
}
