package com.publicmonitor.backend.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.local-admin")
public record LocalAdminProperties(
        boolean enabled,
        String loginId,
        String password
) {
}
