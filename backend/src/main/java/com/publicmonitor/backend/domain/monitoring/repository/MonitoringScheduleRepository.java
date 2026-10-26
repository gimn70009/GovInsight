package com.publicmonitor.backend.domain.monitoring.repository;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSchedule;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MonitoringScheduleRepository extends JpaRepository<MonitoringSchedule, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MonitoringSchedule s order by s.id")
    List<MonitoringSchedule> findAllForUpdate();
}
