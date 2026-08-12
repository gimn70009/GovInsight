package com.publicmonitor.backend.domain.monitoring.web.controller;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringRunService;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringRunResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunSummaryResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/monitoring-runs")
public class MonitoringRunController {

    private final MonitoringRunService monitoringRunService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SuccessResponse<CreateMonitoringRunResponse> create() {
        return SuccessResponse.created(monitoringRunService.create(MonitoringTriggerType.MANUAL));
    }

    @GetMapping
    public SuccessResponse<List<MonitoringRunSummaryResponse>> findAll() {
        return SuccessResponse.ok(monitoringRunService.findAll());
    }
}
