package com.publicmonitor.backend.domain.document.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentDetectionRepository extends JpaRepository<DocumentDetection, Long> {

    List<DocumentDetection> findAllByMonitoringRunSourceIdOrderByDisplayOrderAsc(Long runSourceId);

    boolean existsByMonitoringRunSourceIdAndDocumentId(Long runSourceId, Long documentId);

    @Query(
            value = """
                    select new com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse(
                        detection.id, document.id, version.id,
                        source.organizationName, source.boardName, version.title,
                        detection.changeType, version.attachmentCount, analysis.importance, detection.detectedAt
                    )
                    from DocumentDetection detection
                    join detection.document document
                    join detection.documentVersion version
                    join detection.monitoringRunSource runSource
                    join runSource.monitoringSource source
                    left join DocumentAnalysis analysis on analysis.documentVersion = version
                    order by detection.detectedAt desc, detection.id desc
                    """,
            countQuery = "select count(detection) from DocumentDetection detection"
    )
    Page<DocumentDetectionSummaryResponse> findSummaries(Pageable pageable);
}
