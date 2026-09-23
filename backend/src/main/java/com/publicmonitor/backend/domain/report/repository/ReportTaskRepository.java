package com.publicmonitor.backend.domain.report.repository;

import com.publicmonitor.backend.domain.report.entity.ReportTask;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReportTaskRepository extends JpaRepository<ReportTask, Long> {
    boolean existsByReportId(Long reportId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ReportTask t join fetch t.report r join fetch r.monitoringRun where r.monitoringRun.id = :runId")
    Optional<ReportTask> lockByRunId(@Param("runId") Long runId);

    @Query("select t.report.monitoringRun.id from ReportTask t where t.state <> com.publicmonitor.backend.domain.report.entity.ReportTaskState.DONE and t.availableAt <= :now order by t.availableAt, t.id")
    List<Long> findDue(@Param("now") LocalDateTime now, Pageable pageable);
}
