package com.publicmonitor.backend.domain.analysis.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisDocumentDetectionRepository extends JpaRepository<DocumentDetection, Long> {

    @EntityGraph(attributePaths = {
            "monitoringRunSource.monitoringSource",
            "document",
            "documentVersion"
    })
    List<DocumentDetection> findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(Long runId);
}
