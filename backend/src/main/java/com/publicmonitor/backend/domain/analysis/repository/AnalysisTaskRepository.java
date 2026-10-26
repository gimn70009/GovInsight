package com.publicmonitor.backend.domain.analysis.repository;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisTask;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {
    // Mutations are serialized by the parent monitoring run's pessimistic lock.
    Optional<AnalysisTask> findByRunId(Long runId);

    @Query("select t.run.id from AnalysisTask t where t.state in ('PENDING', 'RUNNING', 'ANALYZED') "
            + "and t.availableAt <= :now order by t.availableAt, t.id")
    List<Long> findDue(LocalDateTime now, Pageable page);

    @Query("select r.id from MonitoringRun r where r.status = 'COLLECTED' "
            + "and not exists (select t.id from AnalysisTask t where t.run = r) "
            + "and not exists (select p.id from MonitoringReport p where p.monitoringRun = r) order by r.id")
    List<Long> findUnqueued(Pageable page);
}
