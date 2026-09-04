package com.publicmonitor.backend.domain.monitoring.repository;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringScheduleRepository extends JpaRepository<MonitoringSchedule, Long> {
}
