package com.publicmonitor.backend.domain.email.web.controller;
import com.publicmonitor.backend.domain.email.EmailReportDeliveryService;
import com.publicmonitor.backend.domain.email.service.*;
import com.publicmonitor.backend.domain.email.web.dto.*;
import com.publicmonitor.backend.global.config.OpenApiConfig;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@Lazy @RestController @Validated @RequiredArgsConstructor
@RequestMapping("/api/email")
@Tag(name = "Email", description = "이메일 보고서 수신 설정과 발송")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class EmailController {
    private final EmailSettingsService settings;
    private final EmailConnectionService connection;
    private final EmailReportDeliveryService delivery;
    @GetMapping("/settings") @Operation(summary = "수신자 및 발신자 설정 상태 조회")
    public SuccessResponse<EmailSettingsResponse> settings() { return SuccessResponse.ok(settings.find()); }
    @PutMapping("/settings") @Operation(summary = "수신자 및 채널 발송 설정 저장")
    public SuccessResponse<EmailSettingsResponse> update(@Valid @RequestBody UpdateEmailSettingsRequest request) { return SuccessResponse.ok(settings.update(request)); }
    @PostMapping("/test-message") @Operation(summary = "저장된 수신자에게 테스트 메일 발송")
    public SuccessResponse<EmailTestResponse> test(@Valid @RequestBody EmailTargetRequest request) { return SuccessResponse.ok(connection.sendTest(request)); }
    @PostMapping("/deliveries/{deliveryId}/retry") @Operation(summary = "실패한 이메일 한 건 재전송")
    public SuccessResponse<EmailRecipientDeliveryResponse> retry(@PathVariable @Min(1) Long deliveryId,
        @Valid @RequestBody EmailRetryRequest request) { return SuccessResponse.ok(delivery.retry(deliveryId, request)); }
}
