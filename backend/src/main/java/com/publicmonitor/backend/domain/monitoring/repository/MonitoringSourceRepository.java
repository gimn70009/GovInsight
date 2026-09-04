package com.publicmonitor.backend.domain.monitoring.repository;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringSourceRepository extends JpaRepository<MonitoringSource, Long> {

    boolean existsByListUrl(String listUrl);

    boolean existsByListUrlAndIdNot(String listUrl, Long id);

    List<MonitoringSource> findAllByOrderByIdDesc();

    List<MonitoringSource> findAllByEnabledTrueOrderByIdAsc();
}
