package com.publicmonitor.backend.domain.monitoring.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.monitoring.sources.initialize", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MonitoringSourceInitializer implements ApplicationRunner {

    private final MonitoringSourceService monitoringSourceService;

    @Override
    public void run(ApplicationArguments args) {
        monitoringSourceService.initializeDefaults();
    }
}
