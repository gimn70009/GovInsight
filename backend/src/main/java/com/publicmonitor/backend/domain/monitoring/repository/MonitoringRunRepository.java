package com.publicmonitor.backend.domain.monitoring.repository;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringRunRepository extends JpaRepository<MonitoringRun, Long> {

    List<MonitoringRun> findAllByOrderByRequestedAtDescIdDesc();
}
