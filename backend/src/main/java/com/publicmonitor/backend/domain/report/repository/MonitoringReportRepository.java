package com.publicmonitor.backend.domain.report.repository;

import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitoringReportRepository extends JpaRepository<MonitoringReport, Long> {
    Optional<MonitoringReport> findByMonitoringRunId(Long runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MonitoringReport r join fetch r.monitoringRun where r.monitoringRun.id = :runId")
    Optional<MonitoringReport> findForTelegramByRunId(@Param("runId") Long runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MonitoringReport r join fetch r.monitoringRun where r.id = :reportId")
    Optional<MonitoringReport> findForTelegramById(@Param("reportId") Long reportId);

    @Query("select r from MonitoringReport r join fetch r.monitoringRun where r.id = :reportId")
    Optional<MonitoringReport> findTelegramDetail(@Param("reportId") Long reportId);

    String HISTORY_FILTER = """
            where (:fromTime is null or r.createdAt >= :fromTime)
              and (:toTime is null or r.createdAt < :toTime)
              and (:deliveryStatus is null or
                  case when r.status = com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus.FAILED then 'REPORT_FAILED'
                       when r.status = com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus.PENDING then 'PREPARING'
                       when exists (select d.id from TelegramDelivery d where d.report = r and d.status = com.publicmonitor.backend.domain.telegram.entity.TelegramDeliveryState.PENDING) then 'SENDING'
                       when exists (select d.id from TelegramDelivery d where d.report = r and d.status = com.publicmonitor.backend.domain.telegram.entity.TelegramDeliveryState.SENT) and exists (select d.id from TelegramDelivery d where d.report = r and d.status = com.publicmonitor.backend.domain.telegram.entity.TelegramDeliveryState.FAILED) then 'PARTIAL'
                       when exists (select d.id from TelegramDelivery d where d.report = r and d.status = com.publicmonitor.backend.domain.telegram.entity.TelegramDeliveryState.FAILED) then 'FAILED'
                       when exists (select d.id from TelegramDelivery d where d.report = r and d.status = com.publicmonitor.backend.domain.telegram.entity.TelegramDeliveryState.SENT) or r.telegramSentAt is not null then 'SENT'
                       when r.telegramErrorMessage is not null then 'FAILED'
                       else 'NOT_SENT' end = :deliveryStatus)
            """;

    @Query(value = "select r from MonitoringReport r join fetch r.monitoringRun " + HISTORY_FILTER
            + " order by r.createdAt desc, r.id desc",
            countQuery = "select count(r) from MonitoringReport r " + HISTORY_FILTER)
    Page<MonitoringReport> findTelegramHistory(
            @Param("fromTime") LocalDateTime fromTime, @Param("toTime") LocalDateTime toTime,
            @Param("deliveryStatus") String deliveryStatus, Pageable pageable);
}
