package com.publicmonitor.backend.domain.analysis.repository;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentAnalysisRepository extends JpaRepository<DocumentAnalysis, Long> {

    boolean existsByDocumentVersionId(Long versionId);

    List<DocumentAnalysis> findAllByDocumentVersionIdIn(Collection<Long> versionIds);
}
