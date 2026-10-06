package com.publicmonitor.backend.domain.analysis.entity;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import jakarta.persistence.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "analysis_tasks", uniqueConstraints = @UniqueConstraint(name = "uk_analysis_tasks_run", columnNames = "run_id"),
        indexes = @Index(name = "ix_analysis_tasks_due", columnList = "state,available_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(name = "analysis_task_seq", sequenceName = "analysis_tasks_sequence", allocationSize = 1)
public class AnalysisTask {
    public enum State { PENDING, RUNNING, ANALYZED, DONE, FAILED }
    @Id @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "analysis_task_seq")
    @Column(name = "task_id") private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false) private MonitoringRun run;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private State state = State.PENDING;
    @Column(name = "request_json", columnDefinition = "CLOB") private String requestJson;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "attempt_id", length = 36) private String attemptId;
    @Column(name = "available_at", nullable = false) private LocalDateTime availableAt;

    public static AnalysisTask pending(MonitoringRun run, LocalDateTime now) {
        var task = new AnalysisTask();
        task.run = run; task.availableAt = now;
        return task;
    }
    public boolean due(LocalDateTime now) {
        return state != State.DONE && state != State.FAILED && !availableAt.isAfter(now);
    }
    public String claim(LocalDateTime now, Duration lease) {
        if (!due(now)) throw new IllegalStateException("아직 실행할 수 없는 분석 작업입니다.");
        state = State.RUNNING; attemptCount++;
        attemptId = UUID.randomUUID().toString(); availableAt = now.plus(lease);
        return attemptId;
    }
    public void saveRequest(String json, LocalDateTime now, Duration lease) {
        requestJson = json; availableAt = now.plus(lease);
    }
    public boolean matches(UUID token, LocalDateTime now) {
        return token != null && token.toString().equals(attemptId)
                && (state == State.DONE || ((state == State.RUNNING || state == State.ANALYZED)
                    && availableAt.isAfter(now)));
    }
    public void analyzed(LocalDateTime now, Duration lease) {
        state = State.ANALYZED; availableAt = now.plus(lease);
    }
    public void retry(LocalDateTime now) { state = State.PENDING; availableAt = now.plusSeconds(10); }
    public void complete() { state = State.DONE; requestJson = null; }
    public void fail(LocalDateTime now) {
        state = State.FAILED; requestJson = null;
        run.failAnalysis("분석·제안 작업 복구 재시도 한도를 초과했습니다. 다시 실행해 주세요.", now);
    }
}
