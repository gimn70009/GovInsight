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

    public static final int DEFAULT_DETAIL_FETCH_COUNT = 3;
    public static final boolean DEFAULT_ENABLED = true;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "monitoring_sources_sequence_generator")
    @Column(name = "source_id")
    private Long id;

    @Column(name = "organization_name", nullable = false, length = 100)
    private String organizationName;

    @Column(name = "board_name", nullable = false, length = 100)
    private String boardName;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "list_url", nullable = false, length = 1000)
    private String listUrl;

    @Column(name = "url_include_pattern", length = 500)
    private String urlIncludePattern;

    @Column(name = "detail_fetch_count", nullable = false, columnDefinition = "NUMBER DEFAULT 3")
    private int detailFetchCount = DEFAULT_DETAIL_FETCH_COUNT;

    @Column(name = "enabled", nullable = false, columnDefinition = "NUMBER(1) DEFAULT 1")
    private boolean enabled = DEFAULT_ENABLED;

    private MonitoringSource(
            String organizationName,
            String boardName,
            String description,
            String listUrl,
            String urlIncludePattern,
            int detailFetchCount,
            boolean enabled
    ) {
        this.organizationName = organizationName;
        this.boardName = boardName;
        this.description = description;
        this.listUrl = listUrl;
        this.urlIncludePattern = urlIncludePattern;
        this.detailFetchCount = detailFetchCount;
        this.enabled = enabled;
    }

    public static MonitoringSource create(
            String organizationName,
            String boardName,
            String description,
            String listUrl,
            String urlIncludePattern,
            int detailFetchCount,
            boolean enabled
    ) {
        return new MonitoringSource(
                organizationName,
                boardName,
                description,
                listUrl,
                urlIncludePattern,
                detailFetchCount,
                enabled
        );
    }
}
