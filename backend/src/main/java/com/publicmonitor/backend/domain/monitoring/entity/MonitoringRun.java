package com.publicmonitor.backend.domain.monitoring.entity;

import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
        name = "monitoring_runs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_monitoring_runs_python_job_id",
                columnNames = "python_job_id"
        ),
        check = {
                @CheckConstraint(
                        name = "ck_monitoring_runs_status",
                        constraint = "status in ('REQUESTED', 'ACCEPTED', 'RUNNING', 'COMPLETED', 'FAILED')"
                ),
                @CheckConstraint(
                        name = "ck_monitoring_runs_trigger_type",
                        constraint = "trigger_type in ('MANUAL', 'SCHEDULED')"
                ),
                @CheckConstraint(
                        name = "ck_monitoring_runs_counts",
                        constraint = "total_source_count >= 0 and success_source_count >= 0 "
                                + "and failed_source_count >= 0 and detected_document_count >= 0 "
                                + "and warning_count >= 0"
                ),
                @CheckConstraint(
                        name = "ck_monitoring_runs_source_counts",
                        constraint = "success_source_count + failed_source_count <= total_source_count"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
        name = "monitoring_runs_sequence_generator",
        sequenceName = "monitoring_runs_sequence",
        allocationSize = 1
)
public class MonitoringRun extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "monitoring_runs_sequence_generator")
    @Column(name = "run_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20,
            columnDefinition = "VARCHAR2(20) DEFAULT 'REQUESTED'"
    )
    private MonitoringRunStatus status = MonitoringRunStatus.REQUESTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private MonitoringTriggerType triggerType;

    @Column(name = "python_job_id", length = 100)
    private String pythonJobId;

    @Column(name = "total_source_count", nullable = false, columnDefinition = "NUMBER DEFAULT 0")
    private int totalSourceCount;

    @Column(name = "success_source_count", nullable = false, columnDefinition = "NUMBER DEFAULT 0")
    private int successSourceCount;

    @Column(name = "failed_source_count", nullable = false, columnDefinition = "NUMBER DEFAULT 0")
    private int failedSourceCount;

    @Column(name = "detected_document_count", nullable = false, columnDefinition = "NUMBER DEFAULT 0")
    private int detectedDocumentCount;

    @Column(name = "warning_count", nullable = false, columnDefinition = "NUMBER DEFAULT 0")
    private int warningCount;

    @Column(name = "requested_at", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime requestedAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    private MonitoringRun(MonitoringTriggerType triggerType, int totalSourceCount, LocalDateTime requestedAt) {
        this.triggerType = triggerType;
        this.totalSourceCount = totalSourceCount;
        this.requestedAt = requestedAt;
    }

    public static MonitoringRun create(
            MonitoringTriggerType triggerType,
            int totalSourceCount,
            LocalDateTime requestedAt
    ) {
        return new MonitoringRun(triggerType, totalSourceCount, requestedAt);
    }
}
