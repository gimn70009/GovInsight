package com.publicmonitor.backend.domain.report.web.controller;
import com.publicmonitor.backend.domain.report.service.ReportDeliveryQueryService;
import com.publicmonitor.backend.domain.report.web.dto.*;
import com.publicmonitor.backend.domain.telegram.web.dto.TelegramDeliveryStatus;
import com.publicmonitor.backend.global.config.OpenApiConfig;
import com.publicmonitor.backend.global.response.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@Lazy @RestController @Validated @RequiredArgsConstructor
@RequestMapping("/api/report-deliveries")
@Tag(name = "Report delivery", description = "텔레그램·이메일 통합 발송 내역")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ReportDeliveryController {
    public enum Channel { ALL, TELEGRAM, EMAIL }
    private final ReportDeliveryQueryService reports;
    @GetMapping @Operation(summary = "보고서별 채널 발송 상태 조회")
    public SuccessResponse<PageResponse<ReportDeliveryResponse>> list(
        @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue = "ALL") Channel channel, @RequestParam(required = false) TelegramDeliveryStatus status) {
        return SuccessResponse.ok(reports.list(page, size, from, to, channel.name(), status == null ? null : status.name()));
    }
    @GetMapping("/{id}") @Operation(summary = "보고서 본문 및 채널별 수신자 발송 결과 조회")
    public SuccessResponse<ReportDeliveryDetailResponse> detail(@PathVariable @Min(1) Long id) { return SuccessResponse.ok(reports.detail(id)); }
}
