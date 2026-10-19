package com.publicmonitor.backend.domain.settings.web.controller;
import com.publicmonitor.backend.domain.settings.service.EvaluationSettingsService;
import com.publicmonitor.backend.domain.settings.web.dto.*;
import com.publicmonitor.backend.global.response.SuccessResponse;
import com.publicmonitor.backend.global.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/evaluation-settings") @RequiredArgsConstructor
@Tag(name = "Evaluation settings", description = "회사 정보와 기회점수 평가 기준")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class EvaluationSettingsController {
    private final EvaluationSettingsService service;
    @GetMapping @Operation(summary = "회사 공통 평가 설정과 선택 목록 조회")
    public SuccessResponse<EvaluationSettingsResponse> find() { return SuccessResponse.ok(service.find()); }
    @PutMapping @Operation(summary = "회사 정보와 평가 기준 저장; 다음 모니터링부터 적용")
    public SuccessResponse<EvaluationSettingsResponse> update(@Valid @RequestBody UpdateEvaluationSettingsRequest request) {
        return SuccessResponse.ok(service.update(request));
    }
}
