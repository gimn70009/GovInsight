package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.publicmonitor.backend.domain.analysis.entity.*;
import com.publicmonitor.backend.domain.analysis.repository.*;
import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.repository.*;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DocumentDetailReadOnlyTest {
    @Test void repeatedReadsReturnSavedSectionsWithoutAccessingRawAttachmentsOrStartingWork() {
        var detections = mock(DocumentDetectionRepository.class);
        var analyses = mock(DocumentAnalysisRepository.class);
        var tasks = mock(AnalysisTaskRepository.class);
        var attachments = mock(DocumentAttachmentRepository.class);
        var snapshots = mock(DocumentDetailSnapshotReader.class);
        var detection = mock(DocumentDetection.class, RETURNS_DEEP_STUBS);
        var version = mock(DocumentVersion.class);
        when(detection.getDocumentVersion()).thenReturn(version);
        when(version.getId()).thenReturn(3L); when(version.getTitle()).thenReturn("이미 분석한 게시글");
        when(detection.getMonitoringRunSource().getMonitoringRun().getStatus()).thenReturn(MonitoringRunStatus.COMPLETED);
        when(detections.findById(4L)).thenReturn(Optional.of(detection));
        var analysis = DocumentAnalysis.create(version, "저장한 요약", "[]", DocumentImportance.NORMAL, "근거",
                AnalysisEligibility.REVIEW_REQUIRED, AnalysisFavorability.NOT_APPLICABLE, "{\"sections\":[]}",
                null, null, null, "[]", "test", LocalDateTime.now());
        when(analyses.findByDocumentVersionId(3L)).thenReturn(Optional.of(analysis));
        var stored = DocumentDetailPayload.empty("PARTIAL");
        when(snapshots.read(3L)).thenReturn(stored);
        var service = new DocumentDetectionDetailService(detections, analyses, tasks, attachments, new ObjectMapper(), snapshots);
        for (int n = 0; n < 3; n++) {
            var response = service.findById(4L);
            assertThat(response.title()).isEqualTo("이미 분석한 게시글");
            assertThat(response.participationGuide()).isSameAs(stored.participationGuide());
            assertThat(response.analysis().summary()).isEqualTo("저장한 요약");
        }
        verify(attachments, never()).findAllByDocumentVersionId(any());
        verify(version, never()).getContentText();
        verify(version, never()).getParticipationRewriteJson();
        verify(version, never()).getSubmissionNotesJson();
        verify(snapshots, times(3)).read(3L);
        when(snapshots.read(3L)).thenReturn(DocumentDetailPayload.empty("PENDING"));
        var pending = service.findById(4L);
        assertThat(pending.title()).isEqualTo("이미 분석한 게시글");
        assertThat(pending.analysis()).isNotNull();
        assertThat(pending.participationGuide().rewriteStatus()).isEqualTo("PENDING");
    }
}
