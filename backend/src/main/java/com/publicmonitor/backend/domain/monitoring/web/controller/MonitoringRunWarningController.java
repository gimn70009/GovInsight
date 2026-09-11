package com.publicmonitor.backend.domain.monitoring.web.controller;

import com.publicmonitor.backend.domain.monitoring.service.MonitoringRunWarningService;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunWarningsResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import com.publicmonitor.backend.global.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.context.annotation.Lazy;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@Validated
@RequestMapping("/api/monitoring-runs")
public class MonitoringRunWarningController {
    private final MonitoringRunWarningService warnings;

    public MonitoringRunWarningController(@Lazy MonitoringRunWarningService warnings) {
        this.warnings = warnings;
    }

    @Operation(summary = "실행 경고 상세", description = "수집 당시 소스·문서·첨부 실패를 조회합니다. 상세 기록이 없는 과거 경고는 건수만 안내합니다.")
    @GetMapping("/{runId}/warnings")
    public SuccessResponse<MonitoringRunWarningsResponse> find(@PathVariable @Min(1) Long runId) {
        return SuccessResponse.ok(warnings.find(runId));
    }
}
