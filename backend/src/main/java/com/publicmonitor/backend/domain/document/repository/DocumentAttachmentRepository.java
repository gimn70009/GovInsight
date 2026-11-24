package com.publicmonitor.backend.domain.document.repository;

import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentAttachmentRepository extends JpaRepository<DocumentAttachment, Long> {

    interface Metadata {
        String getFileName();
        String getFileExtension();
        Long getFileSize();
        com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus getParseStatus();
        String getDownloadUrl();
    }

    @org.springframework.data.jpa.repository.Query("select a.fileName as fileName, a.fileExtension as fileExtension, a.fileSize as fileSize, a.parseStatus as parseStatus, a.downloadUrl as downloadUrl from DocumentAttachment a where a.documentVersion.id = :versionId order by a.id")
    List<Metadata> findMetadataByDocumentVersionId(Long versionId);

    @org.springframework.data.jpa.repository.Query("select count(a) from DocumentAttachment a where a.documentVersion.id = :versionId and a.parseStatus = 'COMPLETED' and a.extractedText is not null and length(a.extractedText) > 0")
    long countReadableByVersionId(Long versionId);

    List<DocumentAttachment> findAllByDocumentVersionId(Long versionId);
}
