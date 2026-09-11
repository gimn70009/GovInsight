package com.publicmonitor.backend.domain.telegram.web.controller;

import com.publicmonitor.backend.domain.telegram.TelegramReportDeliveryService;
import com.publicmonitor.backend.domain.telegram.service.TelegramConnectionService;
import com.publicmonitor.backend.domain.telegram.service.TelegramReportQueryService;
import com.publicmonitor.backend.domain.telegram.service.TelegramSettingsService;
import com.publicmonitor.backend.domain.telegram.web.dto.*;
import com.publicmonitor.backend.global.config.OpenApiConfig;
import com.publicmonitor.backend.global.response.PageResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Lazy
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/telegram")
@Tag(name = "Telegram", description = "텔레그램 보고서 수신 설정과 발송 내역")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class TelegramController {
    private final TelegramSettingsService settings;
    private final TelegramConnectionService connection;
    private final TelegramReportQueryService reports;
    private final TelegramReportDeliveryService delivery;

    @GetMapping("/settings")
    @Operation(summary = "수신 대상과 발송 설정 조회")
    public SuccessResponse<TelegramSettingsResponse> settings() {
        return SuccessResponse.ok(settings.find());
    }

    @PutMapping("/settings")
    @Operation(summary = "수신 대상과 발송 설정 저장")
    public SuccessResponse<TelegramSettingsResponse> update(@Valid @RequestBody UpdateTelegramSettingsRequest request) {
        return SuccessResponse.ok(settings.update(request));
    }

    @PostMapping("/connection-check")
    @Operation(summary = "메시지를 보내지 않고 봇과 채팅 연결 확인")
    public SuccessResponse<TelegramConnectionResponse> check(@Valid @RequestBody TelegramTargetRequest request) {
        return SuccessResponse.ok(connection.check(request));
    }

    @PostMapping("/test-message")
    @Operation(summary = "저장된 수신 대상에 테스트 메시지 발송")
    public SuccessResponse<TelegramTestResponse> test(@Valid @RequestBody TelegramTargetRequest request) {
        return SuccessResponse.ok(connection.sendTest(request));
    }

    @GetMapping("/reports")
    @Operation(summary = "보고서별 최근 발송 상태와 이력 조회")
    public SuccessResponse<PageResponse<TelegramReportResponse>> reports(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TelegramDeliveryStatus status
    ) {
        return SuccessResponse.ok(reports.list(page, size, from, to, status));
    }

    @GetMapping("/reports/{reportId}")
    @Operation(summary = "저장된 보고서 본문 조회")
    public SuccessResponse<TelegramReportDetailResponse> detail(@PathVariable @Min(1) Long reportId) {
        return SuccessResponse.ok(reports.detail(reportId));
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    @Operation(summary = "실패한 수신자에게 저장된 보고서 재전송")
    public SuccessResponse<TelegramRecipientDeliveryResponse> retry(
            @PathVariable @Min(1) Long deliveryId, @Valid @RequestBody TelegramRetryRequest request
    ) {
        return SuccessResponse.ok(delivery.retry(deliveryId, request));
    }
}
