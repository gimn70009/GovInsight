package com.publicmonitor.backend.domain.telegram.repository;

import com.publicmonitor.backend.domain.telegram.entity.TelegramSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramSettingsRepository extends JpaRepository<TelegramSettings, Long> {
}
