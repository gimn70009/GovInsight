package com.publicmonitor.backend.domain.document.entity;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
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
        name = "document_detections",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_detections_run_source_document",
                columnNames = {"run_source_id", "document_id"}
        ),
        check = {
                @CheckConstraint(
                        name = "ck_document_detections_change_type",
                        constraint = "change_type in ('NEW_DOCUMENT', 'UPDATED_DOCUMENT', 'UNCHANGED_DOCUMENT')"
                ),
                @CheckConstraint(
                        name = "ck_document_detections_display_order",
                        constraint = "display_order >= 0"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
        name = "document_detections_sequence_generator",
        sequenceName = "document_detections_sequence",
        allocationSize = 1
)
public class DocumentDetection extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_detections_sequence_generator")
    @Column(name = "detection_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_source_id", nullable = false)
    private MonitoringRunSource monitoringRunSource;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private DocumentVersion documentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 30)
    private DocumentChangeType changeType;

    @Column(name = "display_order", nullable = false, columnDefinition = "NUMBER DEFAULT 0")
    private int displayOrder;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    private DocumentDetection(
            MonitoringRunSource monitoringRunSource,
            Document document,
            DocumentVersion documentVersion,
            DocumentChangeType changeType,
            int displayOrder,
            LocalDateTime detectedAt
    ) {
        this.monitoringRunSource = monitoringRunSource;
        this.document = document;
        this.documentVersion = documentVersion;
        this.changeType = changeType;
        this.displayOrder = displayOrder;
        this.detectedAt = detectedAt;
    }

    public static DocumentDetection create(
            MonitoringRunSource monitoringRunSource,
            Document document,
            DocumentVersion documentVersion,
            DocumentChangeType changeType,
            int displayOrder,
            LocalDateTime detectedAt
    ) {
        return new DocumentDetection(
                monitoringRunSource,
                document,
                documentVersion,
                changeType,
                displayOrder,
                detectedAt
        );
    }
}
