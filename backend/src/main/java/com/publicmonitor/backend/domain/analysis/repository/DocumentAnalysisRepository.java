package com.publicmonitor.backend.domain.analysis.repository;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentAnalysisRepository extends JpaRepository<DocumentAnalysis, Long> {

    boolean existsByDocumentVersionId(Long versionId);

    Optional<DocumentAnalysis> findByDocumentVersionId(Long versionId);

    List<DocumentAnalysis> findAllByDocumentVersionIdIn(Collection<Long> versionIds);
}
