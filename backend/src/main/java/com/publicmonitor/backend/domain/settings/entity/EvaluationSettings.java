package com.publicmonitor.backend.domain.settings.entity;
import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
@Getter @Entity @Table(name = "evaluation_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvaluationSettings extends BaseEntity {
    public static final long ID = 1L;
    @Id @Column(name = "settings_id") private Long id = ID;
    @Version private Long version;
    @Column(name = "settings_data", nullable = false, columnDefinition = "CLOB") private String data;
    public static EvaluationSettings empty() { return new EvaluationSettings(); }
    public void update(String data) { this.data = data; }
    public long revision() { return version == null ? 0 : version + 1; }
}
