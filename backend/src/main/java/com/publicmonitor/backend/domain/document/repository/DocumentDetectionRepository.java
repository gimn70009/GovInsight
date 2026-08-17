package com.publicmonitor.backend.domain.document.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentDetectionRepository extends JpaRepository<DocumentDetection, Long> {

    List<DocumentDetection> findAllByMonitoringRunSourceIdOrderByDisplayOrderAsc(Long runSourceId);
}
