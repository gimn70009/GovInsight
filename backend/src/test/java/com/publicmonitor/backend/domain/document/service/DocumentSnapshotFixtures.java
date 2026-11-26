package com.publicmonitor.backend.domain.document.service;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.repository.*;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** Legacy content fixtures now materialize BEFORE invoking the read service. */
final class DocumentSnapshotFixtures {
    static DocumentDetailSnapshotReader prepare(DocumentDetectionRepository detections,
            DocumentAttachmentRepository attachments, DocumentAnalysisRepository analyses,
            ParticipationRewriteService rewrite, ObjectMapper mapper) {
        return prepare(detections, attachments, analyses, rewrite, SubmissionNotesTestSupport.service(), mapper);
    }
    static DocumentDetailSnapshotReader prepare(DocumentDetectionRepository detections,
            DocumentAttachmentRepository attachments, DocumentAnalysisRepository analyses,
            ParticipationRewriteService rewrite, SubmissionNotesService notes, ObjectMapper mapper) {
        var reader = empty();
        var detection = detections.findById(4L).orElse(null);
        if (detection == null) return reader;
        var version = detection.getDocumentVersion();
        var versions = mock(DocumentVersionRepository.class);
        when(versions.findById(version.getId())).thenReturn(Optional.of(version));
        var payload = new DocumentDetailSnapshotBuilder(versions, attachments, analyses, rewrite,
                notes, mapper).build(version.getId());
        lenient().when(reader.read(version.getId())).thenReturn(payload);
        var files = attachments.findAllByDocumentVersionId(version.getId());
        var metadataRows = files.stream().map(file -> {
            var metadata = mock(DocumentAttachmentRepository.Metadata.class);
            lenient().when(metadata.getFileName()).thenReturn(file.getFileName());
            lenient().when(metadata.getFileExtension()).thenReturn(file.getFileExtension());
            lenient().when(metadata.getFileSize()).thenReturn(file.getFileSize());
            lenient().when(metadata.getParseStatus()).thenReturn(file.getParseStatus());
            lenient().when(metadata.getDownloadUrl()).thenReturn(file.getDownloadUrl());
            return metadata;
        }).toList();
        lenient().when(attachments.findMetadataByDocumentVersionId(version.getId())).thenReturn(metadataRows);
        return reader;
    }
    static DocumentDetailSnapshotReader empty() {
        var reader = mock(DocumentDetailSnapshotReader.class);
        lenient().when(reader.read(any())).thenReturn(DocumentDetailPayload.empty("PENDING"));
        return reader;
    }
}
