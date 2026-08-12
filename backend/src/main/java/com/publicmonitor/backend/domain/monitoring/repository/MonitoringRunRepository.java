package com.publicmonitor.backend.domain.monitoring.repository;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringRunRepository extends JpaRepository<MonitoringRun, Long> {
}
