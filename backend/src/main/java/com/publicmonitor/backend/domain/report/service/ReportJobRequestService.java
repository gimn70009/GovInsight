package com.publicmonitor.backend.domain.report.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Lazy @Service @RequiredArgsConstructor
public class ReportJobRequestService {
    private final ReportPreparationService preparationService;
    private final ReportTaskRunner runner;
    public void request(Long runId) {
        preparationService.prepare(runId);
        runner.run(runId);
    }
}
