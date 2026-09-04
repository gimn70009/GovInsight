package com.publicmonitor.backend.domain.monitoring.web.controller;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringRunService;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringRunResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunSummaryResponse;
import com.publicmonitor.backend.global.config.OpenApiConfig;
import com.publicmonitor.backend.global.response.SuccessResponse;
import com.publicmonitor.backend.global.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Monitoring Runs", description = "모니터링 수동 실행과 실행 이력 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/monitoring-runs")
public class MonitoringRunController {

    private final MonitoringRunService monitoringRunService;

    @Operation(summary = "모니터링 수동 실행", description = "활성화된 모든 소스를 대상으로 모니터링 실행을 생성하고 Python에 작업을 요청합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "실행 생성 및 Python 작업 접수 성공"),
            @ApiResponse(responseCode = "401", description = "인증 필요"),
            @ApiResponse(responseCode = "422", description = "활성화된 모니터링 소스가 없음"),
            @ApiResponse(responseCode = "502", description = "Python 작업 접수 실패")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SuccessResponse<CreateMonitoringRunResponse> create() {
        return SuccessResponse.created(monitoringRunService.create(MonitoringTriggerType.MANUAL));
    }

    @Operation(summary = "모니터링 실행 이력 목록 조회", description = "최근 실행 순서로 전체 모니터링 실행 이력을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "실행 이력 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping
    public SuccessResponse<PageResponse<MonitoringRunSummaryResponse>> findAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return SuccessResponse.ok(monitoringRunService.findAll(page, size));
    }
}
