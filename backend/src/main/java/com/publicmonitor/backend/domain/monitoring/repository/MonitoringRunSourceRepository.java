package com.publicmonitor.backend.domain.monitoring.repository;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitoringRunSourceRepository extends JpaRepository<MonitoringRunSource, Long> {

    List<MonitoringRunSource> findAllByMonitoringRunId(Long runId);

    @Query("select rs from MonitoringRunSource rs join fetch rs.monitoringSource where rs.monitoringRun.id = :runId order by rs.id")
    List<MonitoringRunSource> findWithSourcesByMonitoringRunId(@Param("runId") Long runId);

    Optional<MonitoringRunSource> findByMonitoringRunIdAndMonitoringSourceId(Long runId, Long sourceId);
}
