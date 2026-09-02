package com.publicmonitor.backend.domain.monitoring.web.controller;

import com.publicmonitor.backend.domain.monitoring.service.MonitoringScheduleService;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringScheduleResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.UpdateMonitoringScheduleRequest;
import com.publicmonitor.backend.global.config.OpenApiConfig;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Monitoring Schedule", description = "자동 모니터링 일정 설정 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@ConditionalOnProperty(name = "app.monitoring.schedule.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@RequestMapping("/api/monitoring-schedule")
public class MonitoringScheduleController {

    private final MonitoringScheduleService scheduleService;

    @Operation(summary = "자동 모니터링 일정 조회")
    @GetMapping
    public SuccessResponse<MonitoringScheduleResponse> find() {
        return SuccessResponse.ok(scheduleService.find());
    }

    @Operation(summary = "자동 모니터링 일정 저장")
    @PutMapping
    public SuccessResponse<MonitoringScheduleResponse> update(
            @Valid @RequestBody UpdateMonitoringScheduleRequest request
    ) {
        return SuccessResponse.ok(scheduleService.update(request));
    }
}
