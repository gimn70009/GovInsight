package com.publicmonitor.backend.domain.monitoring.repository;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringRunSourceRepository extends JpaRepository<MonitoringRunSource, Long> {

    List<MonitoringRunSource> findAllByMonitoringRunId(Long runId);

    Optional<MonitoringRunSource> findByMonitoringRunIdAndMonitoringSourceId(Long runId, Long sourceId);
}
