package com.publicmonitor.backend.domain.monitoring.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.python-monitoring")
public record PythonMonitoringClientProperties(
        @DefaultValue("http://localhost:8000") String baseUrl,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout
) {
}
