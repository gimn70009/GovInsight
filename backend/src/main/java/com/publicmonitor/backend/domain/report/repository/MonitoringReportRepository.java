package com.publicmonitor.backend.domain.report.repository;

import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringReportRepository extends JpaRepository<MonitoringReport, Long> {

    Optional<MonitoringReport> findByMonitoringRunId(Long runId);
}
