package com.publicmonitor.backend.domain.document.entity;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
        name = "documents",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_documents_source_original_url",
                columnNames = {"source_id", "original_url"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
        name = "documents_sequence_generator",
        sequenceName = "documents_sequence",
        allocationSize = 1
)
public class Document extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "documents_sequence_generator")
    @Column(name = "document_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private MonitoringSource monitoringSource;

    @Column(name = "original_url", nullable = false, length = 2000)
    private String originalUrl;

    @Column(name = "external_document_id", length = 200)
    private String externalDocumentId;

    @Column(name = "first_detected_at", nullable = false)
    private LocalDateTime firstDetectedAt;

    @Column(name = "last_detected_at", nullable = false)
    private LocalDateTime lastDetectedAt;

    private Document(
            MonitoringSource monitoringSource,
            String originalUrl,
            String externalDocumentId,
            LocalDateTime detectedAt
    ) {
        this.monitoringSource = monitoringSource;
        this.originalUrl = originalUrl;
        this.externalDocumentId = externalDocumentId;
        this.firstDetectedAt = detectedAt;
        this.lastDetectedAt = detectedAt;
    }

    public static Document create(
            MonitoringSource monitoringSource,
            String originalUrl,
            String externalDocumentId,
            LocalDateTime detectedAt
    ) {
        return new Document(monitoringSource, originalUrl, externalDocumentId, detectedAt);
    }

    public void markDetected(LocalDateTime detectedAt) {
        this.lastDetectedAt = detectedAt;
    }
}
