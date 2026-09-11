package com.publicmonitor.backend.domain.telegram.repository;

import com.publicmonitor.backend.domain.telegram.entity.TelegramDelivery;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TelegramDeliveryRepository extends JpaRepository<TelegramDelivery, Long> {
    List<TelegramDelivery> findByReportIdOrderById(Long reportId);
    List<TelegramDelivery> findByReportIdInOrderById(Collection<Long> reportIds);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TelegramDelivery d where d.id = :id")
    Optional<TelegramDelivery> findForUpdate(@Param("id") Long id);
}
