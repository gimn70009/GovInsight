package com.publicmonitor.backend.domain.document.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "document_attachments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_attachments_version_url",
                columnNames = {"version_id", "download_url"}
        ),
        check = {
                @CheckConstraint(
                        name = "ck_document_attachments_file_size",
                        constraint = "file_size is null or file_size >= 0"
                ),
                @CheckConstraint(
                        name = "ck_document_attachments_parse_status",
                        constraint = "parse_status in ('PENDING', 'PARSING', 'COMPLETED', 'FAILED', 'UNSUPPORTED')"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
        name = "document_attachments_sequence_generator",
        sequenceName = "document_attachments_sequence",
        allocationSize = 1
)
public class DocumentAttachment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_attachments_sequence_generator")
    @Column(name = "attachment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private DocumentVersion documentVersion;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "download_url", nullable = false, length = 2000)
    private String downloadUrl;

    @Column(name = "file_extension", length = 20)
    private String fileExtension;

    @Column(name = "content_type", length = 200)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "extracted_text", columnDefinition = "CLOB")
    private String extractedText;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "parse_status",
            nullable = false,
            length = 20,
            columnDefinition = "VARCHAR2(20) DEFAULT 'PENDING'"
    )
    private AttachmentParseStatus parseStatus = AttachmentParseStatus.PENDING;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    private DocumentAttachment(
            DocumentVersion documentVersion,
            String fileName,
            String downloadUrl,
            String fileExtension,
            String contentType,
            Long fileSize,
            String fileHash,
            String extractedText,
            AttachmentParseStatus parseStatus,
            String errorMessage
    ) {
        this.documentVersion = documentVersion;
        this.fileName = fileName;
        this.downloadUrl = downloadUrl;
        this.fileExtension = fileExtension;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.extractedText = extractedText;
        this.parseStatus = parseStatus;
        this.errorMessage = errorMessage;
    }

    public static DocumentAttachment create(
            DocumentVersion documentVersion,
            String fileName,
            String downloadUrl,
            String fileExtension,
            String contentType,
            Long fileSize,
            String fileHash
    ) {
        return create(
                documentVersion,
                fileName,
                downloadUrl,
                fileExtension,
                contentType,
                fileSize,
                fileHash,
                null,
                AttachmentParseStatus.PENDING,
                null
        );
    }

    public void updateDownloadMetadata(
            String contentType,
            Long fileSize,
            String fileHash,
            String extractedText,
            AttachmentParseStatus parseStatus,
            String errorMessage
    ) {
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.extractedText = extractedText;
        this.parseStatus = parseStatus;
        this.errorMessage = errorMessage;
    }
    public static DocumentAttachment create(
            DocumentVersion documentVersion,
            String fileName,
            String downloadUrl,
            String fileExtension,
            String contentType,
            Long fileSize,
            String fileHash,
            String extractedText,
            AttachmentParseStatus parseStatus,
            String errorMessage
    ) {
        return new DocumentAttachment(
                documentVersion,
                fileName,
                downloadUrl,
                fileExtension,
                contentType,
                fileSize,
                fileHash,
                extractedText,
                parseStatus,
                errorMessage
        );
    }
}