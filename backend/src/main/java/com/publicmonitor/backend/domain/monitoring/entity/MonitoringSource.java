package com.publicmonitor.backend.domain.monitoring.entity;

import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "monitoring_sources",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_monitoring_sources_list_url",
                columnNames = "list_url"
        ),
        check = {
                @CheckConstraint(
                        name = "ck_monitoring_sources_enabled",
                        constraint = "enabled in (0, 1)"
                ),
                @CheckConstraint(
                        name = "ck_monitoring_sources_detail_fetch_count",
                        constraint = "detail_fetch_count >= 1"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
        name = "monitoring_sources_sequence_generator",
        sequenceName = "monitoring_sources_sequence",
        allocationSize = 1
)
public class MonitoringSource extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "monitoring_sources_sequence_generator")
    @Column(name = "source_id")
    private Long id; // 시퀀스(소스 식별자)

    @Column(name = "organization_name", nullable = false, length = 100)
    private String organizationName; // 기관명

    @Column(name = "board_name", nullable = false, length = 100)
    private String boardName; // 게시판명

    @Column(name = "description", length = 1000)
    private String description; // 설명

    @Column(name = "list_url", nullable = false, length = 1000)
    private String listUrl; // 시작 URL

    @Column(name = "url_include_pattern", length = 500)
    private String urlIncludePattern; // 포함 URL 패턴

    @Column(name = "detail_fetch_count", nullable = false, columnDefinition = "NUMBER DEFAULT 3")
    private int detailFetchCount = 3; // 실행 시 가지고 올 상세 게시글 수

    @Column(name = "enabled", nullable = false, columnDefinition = "NUMBER(1) DEFAULT 1")
    private boolean enabled = true; // 활성 여부
}
