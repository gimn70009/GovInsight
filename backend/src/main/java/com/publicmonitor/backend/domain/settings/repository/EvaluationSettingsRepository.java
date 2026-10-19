package com.publicmonitor.backend.domain.settings.repository;
import com.publicmonitor.backend.domain.settings.entity.EvaluationSettings;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EvaluationSettingsRepository extends JpaRepository<EvaluationSettings, Long> {}
