package com.publicmonitor.backend.domain.report.entity;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "monitoring_reports",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_monitoring_reports_run",
                columnNames = "run_id"
        ),
        check = @CheckConstraint(
                name = "ck_monitoring_reports_status",
                constraint = "status in ('PENDING', 'COMPLETED', 'FAILED')"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
        name = "monitoring_reports_sequence_generator",
        sequenceName = "monitoring_reports_sequence",
        allocationSize = 1
)
public class MonitoringReport extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "monitoring_reports_sequence_generator")
    @Column(name = "report_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private MonitoringRun monitoringRun;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "summary", columnDefinition = "CLOB")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20,
            columnDefinition = "VARCHAR2(20) DEFAULT 'PENDING'"
    )
    private MonitoringReportStatus status = MonitoringReportStatus.PENDING;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "telegram_message_id")
    private Long telegramMessageId;

    @Column(name = "telegram_sent_at")
    private LocalDateTime telegramSentAt;

    @Column(name = "telegram_error_message", length = 2000)
    private String telegramErrorMessage;

    private MonitoringReport(MonitoringRun monitoringRun) {
        this.monitoringRun = monitoringRun;
    }

    public static MonitoringReport pending(MonitoringRun monitoringRun) {
        return new MonitoringReport(monitoringRun);
    }

    public void prepareRetry() {
        if (status != MonitoringReportStatus.FAILED) {
            throw new IllegalStateException("실패한 보고서만 다시 준비할 수 있습니다.");
        }
        this.status = MonitoringReportStatus.PENDING;
        this.errorMessage = null;
        this.generatedAt = null;
    }

    public void complete(String title, String summary, LocalDateTime generatedAt) {
        if (status != MonitoringReportStatus.PENDING) {
            throw new IllegalStateException("대기 중인 보고서만 완료할 수 있습니다.");
        }
        this.title = title;
        this.summary = summary;
        this.status = MonitoringReportStatus.COMPLETED;
        this.generatedAt = generatedAt;
        this.errorMessage = null;
    }

    public boolean isTelegramSent() {
        return telegramSentAt != null;
    }

    public void completeTelegramDelivery(Long messageId, LocalDateTime sentAt) {
        this.telegramMessageId = messageId;
        this.telegramSentAt = sentAt;
        this.telegramErrorMessage = null;
    }

    public void failTelegramDelivery(String errorMessage) {
        this.telegramErrorMessage = errorMessage;
    }
    public void fail(String errorMessage) {
        if (status == MonitoringReportStatus.COMPLETED) {
            throw new IllegalStateException("완료된 보고서는 실패로 변경할 수 없습니다.");
        }
        this.status = MonitoringReportStatus.FAILED;
        this.errorMessage = errorMessage;
    }
}
