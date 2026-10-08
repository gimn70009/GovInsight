package com.publicmonitor.backend.domain.analysis.repository;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentAnalysisRepository extends JpaRepository<DocumentAnalysis, Long> {

    @Query("""
            select analysis from DocumentAnalysis analysis
            join fetch analysis.documentVersion version
            join fetch version.document document
            join fetch document.monitoringSource
            where document.id <> :documentId
              and not exists (select newer.id from DocumentAnalysis newer
                              where newer.documentVersion.document.id = document.id
                                and newer.documentVersion.versionNo > version.versionNo)
            """)
    List<DocumentAnalysis> findLatestSimilarityCandidates(@Param("documentId") Long documentId);

    interface SimilarityRevision {
        long getTotal();
        Long getLastId();
        java.time.LocalDateTime getChangedAt();
    }

    @Query("select count(a) as total, max(a.id) as lastId, max(a.updatedAt) as changedAt from DocumentAnalysis a")
    SimilarityRevision similarityRevision();

    interface RankingBackfillRow {
        Long getId();
        Integer getOpportunityScore();
        String getOpportunityAssessment();
    }

    @Query("""
            select a.id as id, a.opportunityScore as opportunityScore,
                   a.opportunityAssessment as opportunityAssessment
            from DocumentAnalysis a
            where a.id > :afterId
              and (a.opportunityRankingVersion is null or a.opportunityRankingVersion < :currentVersion)
            order by a.id
            """)
    @Transactional(readOnly = true)
    List<RankingBackfillRow> findRankingsForBackfill(
            @Param("afterId") long afterId, @Param("currentVersion") int currentVersion, Pageable pageable);

    @Modifying
    @Query("""
            update DocumentAnalysis a
            set a.opportunityScore = :score, a.opportunityPriority = :priority,
                a.opportunityRankingVersion = :currentVersion
            where a.id = :id
              and (a.opportunityRankingVersion is null or a.opportunityRankingVersion < :currentVersion)
            """)
    int backfillRanking(@Param("id") Long id, @Param("score") Integer score,
            @Param("priority") OpportunityPriority priority, @Param("currentVersion") int currentVersion);

    boolean existsByDocumentVersionId(Long versionId);

    Optional<DocumentAnalysis> findByDocumentVersionId(Long versionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DocumentAnalysis a where a.documentVersion.id = :versionId")
    Optional<DocumentAnalysis> findByDocumentVersionIdForUpdate(@Param("versionId") Long versionId);

    List<DocumentAnalysis> findAllByDocumentVersionIdIn(Collection<Long> versionIds);
}
