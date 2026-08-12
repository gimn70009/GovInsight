package com.publicmonitor.backend.domain.monitoring.entity;

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
import jakarta.persistence.ManyToOne;
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
        name = "monitoring_run_sources",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_monitoring_run_sources_run_source",
                columnNames = {"run_id", "source_id"}
        ),
        check = {
                @CheckConstraint(
                        name = "ck_monitoring_run_sources_status",
                        constraint = "status in ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')"
                ),
                @CheckConstraint(
                        name = "ck_monitoring_run_sources_mode",
                        constraint = "processing_mode in ('NORMAL', 'FALLBACK')"
                ),
                @CheckConstraint(
                        name = "ck_monitoring_run_sources_counts",
                        constraint = "detected_document_count >= 0 and warning_count >= 0"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
        name = "monitoring_run_sources_sequence_generator",
        sequenceName = "monitoring_run_sources_sequence",
        allocationSize = 1
)
public class MonitoringRunSource extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "monitoring_run_sources_sequence_generator")
    @Column(name = "run_source_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private MonitoringRun monitoringRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private MonitoringSource monitoringSource;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20,
            columnDefinition = "VARCHAR2(20) DEFAULT 'PENDING'"
    )
    private MonitoringRunSourceStatus status = MonitoringRunSourceStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "processing_mode",
            nullable = false,
            length = 20,
            columnDefinition = "VARCHAR2(20) DEFAULT 'NORMAL'"
    )
    private MonitoringProcessingMode processingMode = MonitoringProcessingMode.NORMAL;

    @Column(name = "detected_document_count", nullable = false, columnDefinition = "NUMBER DEFAULT 0")
    private int detectedDocumentCount;

    @Column(name = "warning_count", nullable = false, columnDefinition = "NUMBER DEFAULT 0")
    private int warningCount;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    private MonitoringRunSource(MonitoringRun monitoringRun, MonitoringSource monitoringSource) {
        this.monitoringRun = monitoringRun;
        this.monitoringSource = monitoringSource;
    }

    public static MonitoringRunSource create(
            MonitoringRun monitoringRun,
            MonitoringSource monitoringSource
    ) {
        return new MonitoringRunSource(monitoringRun, monitoringSource);
    }
}
