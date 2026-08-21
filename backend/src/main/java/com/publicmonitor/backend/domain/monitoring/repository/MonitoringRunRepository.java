package com.publicmonitor.backend.domain.monitoring.repository;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MonitoringRunRepository extends JpaRepository<MonitoringRun, Long> {

    @Query(
            value = """
                    select new com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunSummaryResponse(
                        run.id, run.requestedAt, run.triggerType, run.status,
                        run.totalSourceCount, run.detectedDocumentCount, run.warningCount, report.title
                    )
                    from MonitoringRun run
                    left join MonitoringReport report on report.monitoringRun = run
                    order by run.requestedAt desc, run.id desc
                    """,
            countQuery = "select count(run) from MonitoringRun run"
    )
    Page<MonitoringRunSummaryResponse> findSummaries(Pageable pageable);
}
