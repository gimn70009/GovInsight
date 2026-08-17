package com.publicmonitor.backend.domain.document.repository;

import com.publicmonitor.backend.domain.document.entity.Document;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByMonitoringSourceIdAndOriginalUrl(Long sourceId, String originalUrl);
}
