package com.publicmonitor.backend.domain.monitoring.repository;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoringSourceRepository extends JpaRepository<MonitoringSource, Long> {

    boolean existsByListUrl(String listUrl);

    boolean existsByListUrlAndIdNot(String listUrl, Long id);

    List<MonitoringSource> findAllByOrderByIdDesc();

    List<MonitoringSource> findAllByEnabledTrueOrderByIdAsc();

    // 비활성 소스도 포함해 모든 실행 요청이 같은 행들을 같은 순서로 잠근다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from MonitoringSource s order by s.id")
    List<MonitoringSource> findAllForRunCreation();
}
