package com.publicmonitor.backend.domain.telegram.service;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import com.publicmonitor.backend.domain.telegram.exception.*;
import com.publicmonitor.backend.domain.telegram.repository.TelegramDeliveryRepository;
import com.publicmonitor.backend.domain.telegram.web.dto.*;
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
public class TelegramReportQueryService {
    private final MonitoringReportRepository repository;
    private final TelegramDeliveryRepository deliveries;
    @Transactional(readOnly = true)
    public PageResponse<TelegramReportResponse> list(int page, int size, LocalDate from, LocalDate to, TelegramDeliveryStatus status) {
        if (from != null && to != null && from.isAfter(to)) throw new TelegramException(TelegramResponseCode.INVALID_DATE_RANGE);
        var reports = repository.findTelegramHistory(from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(), status == null ? null : status.name(), PageRequest.of(page, size));
        if (reports.isEmpty()) return PageResponse.from(reports.map(r -> TelegramReportResponse.from(r, List.of())));
        var grouped = deliveries.findByReportIdInOrderById(reports.map(r -> r.getId()).getContent()).stream()
                .collect(Collectors.groupingBy(d -> d.getReport().getId()));
        return PageResponse.from(reports.map(r -> TelegramReportResponse.from(r, grouped.getOrDefault(r.getId(), List.of()))));
    }
    @Transactional(readOnly = true)
    public TelegramReportDetailResponse detail(Long id) {
        var report = repository.findTelegramDetail(id).orElseThrow(() -> new TelegramException(TelegramResponseCode.REPORT_NOT_FOUND));
        var targets = deliveries.findByReportIdOrderById(id);
        return new TelegramReportDetailResponse(TelegramReportResponse.from(report, targets), report.getSummary(),
                targets.stream().map(TelegramRecipientDeliveryResponse::from).toList());
    }
}
