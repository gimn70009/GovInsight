package com.publicmonitor.backend.domain.email.repository;

import com.publicmonitor.backend.domain.email.entity.EmailDelivery;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, Long> {
    List<EmailDelivery> findByReportIdOrderById(Long reportId);
    List<EmailDelivery> findByReportIdInOrderById(Collection<Long> reportIds);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from EmailDelivery d where d.id = :id")
    Optional<EmailDelivery> findForUpdate(@Param("id") Long id);
}
