package com.publicmonitor.backend.domain.settings.entity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
@Getter @Entity @Table(name = "evaluation_settings_revisions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluationSettingsRevision {
    @Id @Column(name = "settings_revision") private Long revision;
    @Column(name = "settings_data", nullable = false, columnDefinition = "CLOB") private String data;
    public EvaluationSettingsRevision(long revision, String data) {
        this.revision = revision; this.data = data;
    }
}
