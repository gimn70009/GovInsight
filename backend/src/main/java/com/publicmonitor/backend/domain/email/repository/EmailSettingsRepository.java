package com.publicmonitor.backend.domain.email.repository;

import com.publicmonitor.backend.domain.email.entity.EmailSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSettingsRepository extends JpaRepository<EmailSettings, Long> {
}
