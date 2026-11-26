package com.publicmonitor.backend.domain.document.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Durable display data and its bounded background refresh job, one per document version. */
@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "document_detail_snapshots", indexes = @Index(name = "ix_detail_snapshot_due", columnList = "state,available_at"))
public class DocumentDetailSnapshot implements org.springframework.data.domain.Persistable<Long> {
    public enum State { QUEUED, BUILDING, READY, FAILED }
    @Id @Column(name = "version_id") private Long versionId;
    @MapsId @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "version_id") private DocumentVersion version;
    @Column(nullable = false) private long revision;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private State state;
    @Column(name = "policy_version", length = 60) private String policyVersion;
    @Column(name = "payload_json", columnDefinition = "CLOB") private String payloadJson;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "attempt_id", length = 36) private String attemptId;
    @Column(name = "available_at", nullable = false) private LocalDateTime availableAt;
    @Transient private boolean fresh = true;
    @Override public Long getId() { return versionId; }
    @Override public boolean isNew() { return fresh; }
    @PostLoad @PostPersist private void persisted() { fresh = false; }

    public static DocumentDetailSnapshot pending(DocumentVersion version, LocalDateTime now) {
        var row = new DocumentDetailSnapshot();
        row.version = version; row.versionId = version.getId(); row.state = State.QUEUED; row.availableAt = now;
        return row;
    }
    public boolean due(LocalDateTime now, String policy) {
        if (state == State.BUILDING) return !availableAt.isAfter(now);
        return !policy.equals(policyVersion) || ((state == State.QUEUED || state == State.READY) && !availableAt.isAfter(now));
    }
    public String claim(LocalDateTime now, String policy) {
        if (!due(now, policy)) return null;
        if (!policy.equals(policyVersion)) attemptCount = 0;
        if (attemptCount >= 3) { state = State.FAILED; attemptId = null; return null; }
        policyVersion = policy; state = State.BUILDING; attemptCount++;
        attemptId = java.util.UUID.randomUUID().toString(); availableAt = now.plusMinutes(3);
        return attemptId;
    }
}
