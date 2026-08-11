package com.publicmonitor.backend.domain.monitoring.web.controller;

import com.publicmonitor.backend.domain.monitoring.service.MonitoringSourceService;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringSourceRequest;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringSourceResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/monitoring-sources")
public class MonitoringSourceController {

    private final MonitoringSourceService monitoringSourceService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SuccessResponse<MonitoringSourceResponse> create(
            @Valid @RequestBody CreateMonitoringSourceRequest request
    ) {
        return SuccessResponse.created(monitoringSourceService.create(request));
    }

    @GetMapping
    public SuccessResponse<List<MonitoringSourceResponse>> findAll() {
        return SuccessResponse.ok(monitoringSourceService.findAll());
    }

    @GetMapping("/{sourceId}")
    public SuccessResponse<MonitoringSourceResponse> findById(
            @Positive @PathVariable Long sourceId
    ) {
        return SuccessResponse.ok(monitoringSourceService.findById(sourceId));
    }
}
