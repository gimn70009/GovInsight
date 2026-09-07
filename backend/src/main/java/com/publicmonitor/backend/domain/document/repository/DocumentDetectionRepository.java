package com.publicmonitor.backend.domain.document.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryRow;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentDetectionRepository extends JpaRepository<DocumentDetection, Long> {

    String SUMMARY_QUERY = """
            select new com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryRow(
                runSource.monitoringRun.id, detection.id, document.id, version.id,
                source.organizationName, source.boardName, version.title,
                detection.changeType, version.attachmentCount, analysis.importance,
                analysis.opportunityScore, analysis.opportunityAssessment, detection.detectedAt
            )
            from DocumentDetection detection
            join detection.document document
            join detection.documentVersion version
            join detection.monitoringRunSource runSource
            join runSource.monitoringSource source
            left join DocumentAnalysis analysis on analysis.documentVersion = version
            where (:runId is null or runSource.monitoringRun.id = :runId)
              and (:fromDateTime is null or detection.detectedAt >= :fromDateTime)
              and (:toDateTime is null or detection.detectedAt <= :toDateTime)
            """;

    String SUMMARY_COUNT_QUERY = """
            select count(detection)
            from DocumentDetection detection
            join detection.monitoringRunSource runSource
            where (:runId is null or runSource.monitoringRun.id = :runId)
              and (:fromDateTime is null or detection.detectedAt >= :fromDateTime)
              and (:toDateTime is null or detection.detectedAt <= :toDateTime)
            """;

    boolean existsByMonitoringRunSourceIdAndDocumentId(Long runSourceId, Long documentId);

    Optional<DocumentDetection> findTopByDocumentIdOrderByDetectedAtDescIdDesc(Long documentId);

    @Query(value = SUMMARY_QUERY + " order by detection.detectedAt desc, detection.id desc", countQuery = SUMMARY_COUNT_QUERY)
    Page<DocumentDetectionSummaryRow> findSummaries(
            @Param("runId") Long runId,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTime") LocalDateTime toDateTime,
            Pageable pageable
    );

    @Query(
            value = SUMMARY_QUERY + """
                    order by
                        case when analysis.opportunityScore is null then 1 else 0 end,
                        analysis.opportunityScore desc,
                        detection.detectedAt desc,
                        detection.id desc
                    """,
            countQuery = SUMMARY_COUNT_QUERY
    )
    Page<DocumentDetectionSummaryRow> findSummariesOrderByOpportunityScore(
            @Param("runId") Long runId,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTime") LocalDateTime toDateTime,
            Pageable pageable
    );

    String BOOKMARK_FILTER = """
              and exists (select b.id from DocumentBookmark b
                          where b.user.id = :userId and b.version.id = detection.documentVersion.id)
              and not exists (select newer.id from DocumentDetection newer
                              where newer.documentVersion.id = detection.documentVersion.id
                                and (newer.detectedAt > detection.detectedAt
                                     or (newer.detectedAt = detection.detectedAt and newer.id > detection.id)))
            """;

    @Query(value = SUMMARY_QUERY + BOOKMARK_FILTER + """
            order by case when :byScore = true then coalesce(analysis.opportunityScore, -1) else 0 end desc,
                     detection.detectedAt desc, detection.id desc
            """, countQuery = SUMMARY_COUNT_QUERY + BOOKMARK_FILTER)
    Page<DocumentDetectionSummaryRow> findBookmarkedSummaries(
            @Param("userId") Long userId, @Param("runId") Long runId,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTime") LocalDateTime toDateTime,
            @Param("byScore") boolean byScore, Pageable pageable);
}
