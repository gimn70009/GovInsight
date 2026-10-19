package com.publicmonitor.backend.domain.settings.repository;
import com.publicmonitor.backend.domain.settings.entity.EvaluationSettingsRevision;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EvaluationSettingsRevisionRepository extends JpaRepository<EvaluationSettingsRevision, Long> {}
