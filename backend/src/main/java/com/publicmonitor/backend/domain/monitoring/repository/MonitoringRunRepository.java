package com.publicmonitor.backend.domain.monitoring.repository;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunSummaryResponse;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MonitoringRunRepository extends JpaRepository<MonitoringRun, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MonitoringRun r where r.id = :id")
    java.util.Optional<MonitoringRun> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    boolean existsByStatusIn(Collection<MonitoringRunStatus> statuses);

    @Query("""
            select r.id from MonitoringRun r
            where r.status in :statuses and coalesce(r.acceptedAt, r.requestedAt) <= :cutoff
            order by r.requestedAt, r.id
            """)
    java.util.List<Long> findExpiredCollections(
            @org.springframework.data.repository.query.Param("statuses") Collection<MonitoringRunStatus> statuses,
            @org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff,
            Pageable pageable);

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
