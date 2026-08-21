package com.publicmonitor.backend.domain.analysis.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisDocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    Optional<DocumentVersion> findByDocumentIdAndVersionNo(Long documentId, int versionNo);
}
