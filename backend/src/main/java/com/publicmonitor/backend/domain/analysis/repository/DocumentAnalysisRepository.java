package com.publicmonitor.backend.domain.analysis.repository;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    boolean existsByDocumentVersionId(Long versionId);

    Optional<DocumentAnalysis> findByDocumentVersionId(Long versionId);

    List<DocumentAnalysis> findAllByDocumentVersionIdIn(Collection<Long> versionIds);
}
