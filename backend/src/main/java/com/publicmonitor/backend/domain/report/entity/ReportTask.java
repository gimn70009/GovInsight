package com.publicmonitor.backend.domain.report.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "report_tasks", uniqueConstraints = @UniqueConstraint(name = "uk_report_tasks_report", columnNames = "report_id"),
        indexes = @Index(name = "ix_report_tasks_due", columnList = "state,available_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(name = "report_task_seq", sequenceName = "report_tasks_sequence", allocationSize = 1)
public class ReportTask {
    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "report_task_seq")
    @Column(name = "task_id") private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false) private MonitoringReport report;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private ReportTaskState state = ReportTaskState.PENDING;
    @Column(name = "request_json", columnDefinition = "CLOB") private String requestJson;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "attempt_id", length = 36) private String attemptId;
    @Column(name = "available_at", nullable = false) private LocalDateTime availableAt;
    @Column(name = "last_error", length = 500) private String lastError;

    public static ReportTask pending(MonitoringReport report, String requestJson, LocalDateTime now) {
        var task = new ReportTask();
        task.report = report; task.requestJson = requestJson; task.availableAt = now;
        return task;
    }
    public boolean due(LocalDateTime now) { return state != ReportTaskState.DONE && !availableAt.isAfter(now); }
    public String claim(LocalDateTime now) {
        if (!due(now)) throw new IllegalStateException("아직 실행할 수 없는 보고서 작업입니다.");
        if (state == ReportTaskState.PENDING || state == ReportTaskState.RUNNING) {
            state = ReportTaskState.RUNNING; attemptCount++;
        } else { state = ReportTaskState.DELIVERING; }
        attemptId = UUID.randomUUID().toString();
        availableAt = now.plusMinutes(2);
        return attemptId;
    }
    public boolean matches(String token) { return token != null && token.equals(attemptId); }
    public void retryGeneration(LocalDateTime now) {
        state = ReportTaskState.PENDING; availableAt = now.plusSeconds(10);
        lastError = "보고서 보강 요청 실패";
    }
    public void completeGeneration(String title, String summary, LocalDateTime now, String reason) {
        report.complete(title, summary, now);
        report.getMonitoringRun().completeReport(now);
        readyForDelivery(now, reason);
    }
    public void readyForDelivery(LocalDateTime now, String reason) {
        state = ReportTaskState.DELIVERY_PENDING; availableAt = now;
        requestJson = null; lastError = reason;
    }
    public void delivered() { state = ReportTaskState.DONE; lastError = null; }
    public void retryDelivery(LocalDateTime now) {
        state = ReportTaskState.DELIVERY_PENDING; availableAt = now.plusMinutes(1);
        lastError = "보고서 발송 작업 복구 대기";
    }
}
