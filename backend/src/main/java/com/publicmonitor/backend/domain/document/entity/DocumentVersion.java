package com.publicmonitor.backend.domain.document.entity;

import com.publicmonitor.backend.global.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
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
        name = "document_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_versions_document_version",
                columnNames = {"document_id", "version_no"}
        ),
        check = {
                @CheckConstraint(
                        name = "ck_document_versions_version_no",
                        constraint = "version_no >= 1"
                ),
                @CheckConstraint(
                        name = "ck_document_versions_attachment_count",
                        constraint = "attachment_count >= 0"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
        name = "document_versions_sequence_generator",
        sequenceName = "document_versions_sequence",
        allocationSize = 1
)
public class DocumentVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_versions_sequence_generator")
    @Column(name = "version_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "content_text", columnDefinition = "CLOB")
    private String contentText;

    @Column(name = "version_hash", nullable = false, length = 64)
    private String versionHash;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "attachment_count", nullable = false, columnDefinition = "NUMBER DEFAULT 0")
    private int attachmentCount;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    private DocumentVersion(
            Document document,
            int versionNo,
            String title,
            String contentText,
            String versionHash,
            LocalDateTime publishedAt,
            int attachmentCount,
            LocalDateTime collectedAt
    ) {
        this.document = document;
        this.versionNo = versionNo;
        this.title = title;
        this.contentText = contentText;
        this.versionHash = versionHash;
        this.publishedAt = publishedAt;
        this.attachmentCount = attachmentCount;
        this.collectedAt = collectedAt;
    }

    public static DocumentVersion create(
            Document document,
            int versionNo,
            String title,
            String contentText,
            String versionHash,
            LocalDateTime publishedAt,
            int attachmentCount,
            LocalDateTime collectedAt
    ) {
        return new DocumentVersion(
                document,
                versionNo,
                title,
                contentText,
                versionHash,
                publishedAt,
                attachmentCount,
                collectedAt
        );
    }
}
