package com.publicmonitor.backend.domain.document.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentDetailSnapshot;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface DocumentDetailSnapshotRepository extends JpaRepository<DocumentDetailSnapshot, Long> {
    @Query("select v.id from DocumentVersion v where not exists (select s.versionId from DocumentDetailSnapshot s where s.versionId = v.id) order by v.id")
    List<Long> findMissing(Pageable page);
    @Query("select s.versionId from DocumentDetailSnapshot s where (s.state <> 'BUILDING' and (s.policyVersion is null or s.policyVersion <> :policy)) or (s.state in ('QUEUED','BUILDING','READY') and s.availableAt <= :now) order by s.availableAt, s.versionId")
    List<Long> findDue(LocalDateTime now, String policy, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DocumentDetailSnapshot s where s.versionId = :id")
    Optional<DocumentDetailSnapshot> findForUpdate(Long id);

    @Modifying
    @Query("update DocumentDetailSnapshot s set s.revision = s.revision + 1, s.state = 'QUEUED', s.attemptCount = 0, s.attemptId = null, s.availableAt = :now where s.versionId = :id")
    int invalidate(Long id, LocalDateTime now);
    @Modifying
    @Query("update DocumentDetailSnapshot s set s.revision = s.revision + 1, s.state = 'QUEUED', s.attemptCount = 0, s.attemptId = null, s.availableAt = :now where s.versionId in (select d.documentVersion.id from DocumentDetection d where d.monitoringRunSource.monitoringRun.id = :runId)")
    int invalidateRun(Long runId, LocalDateTime now);
    @Modifying
    @Query("update DocumentDetailSnapshot s set s.payloadJson = :json, s.state = 'READY', s.attemptCount = 0, s.attemptId = null, s.availableAt = :next where s.versionId = :id and s.revision = :revision and s.attemptId = :token and s.state = 'BUILDING'")
    int complete(Long id, long revision, String token, String json, LocalDateTime next);
    @Modifying
    @Query("update DocumentDetailSnapshot s set s.state = case when s.attemptCount >= 3 then 'FAILED' else 'QUEUED' end, s.attemptId = null, s.availableAt = :next where s.versionId = :id and s.revision = :revision and s.attemptId = :token and s.state = 'BUILDING'")
    int fail(Long id, long revision, String token, LocalDateTime next);
}
