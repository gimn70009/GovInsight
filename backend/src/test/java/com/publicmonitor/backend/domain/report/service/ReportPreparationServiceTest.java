package com.publicmonitor.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentDetectionRepository;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReportPreparationServiceTest {
    @Test void includesSavedSourceComparisonAndDownloadLinksWithoutReanalyzing() {
        var runs = mock(MonitoringRunRepository.class);
        var detections = mock(AnalysisDocumentDetectionRepository.class);
        var analyses = mock(DocumentAnalysisRepository.class);
        var reports = mock(MonitoringReportRepository.class);
        var attachments = mock(DocumentAttachmentRepository.class);
        var tasks = mock(com.publicmonitor.backend.domain.report.repository.ReportTaskRepository.class);
        var run = mock(MonitoringRun.class);
        when(run.getStatus()).thenReturn(MonitoringRunStatus.COLLECTED);
        when(run.getId()).thenReturn(10L);
        when(runs.findForUpdate(10L)).thenReturn(Optional.of(run));
        when(reports.findByMonitoringRunId(10L)).thenReturn(Optional.empty());
        when(reports.save(any())).thenReturn(MonitoringReport.pending(run));
        var detection = mock(DocumentDetection.class, RETURNS_DEEP_STUBS);
        when(detection.getDocumentVersion().getId()).thenReturn(2L);
        when(detection.getDocumentVersion().getContentText()).thenReturn("제출처: 온라인 신청 시스템");
        when(detections.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(10L)).thenReturn(List.of(detection));
        var analysis = mock(DocumentAnalysis.class, RETURNS_DEEP_STUBS);
        when(analysis.getDocumentVersion().getId()).thenReturn(2L);
        when(analysis.getProposalDirection()).thenReturn("""
                {"preparation": {
                  "submissionDocuments": [{"title":"국문 연구개발계획서","stage":"APPLICATION"}],
                  "companyInputs": [{"title":"사업자등록증 사본","stage":"APPLICATION"}]
                }}
                """);
        when(analysis.getComparisonSummary()).thenReturn("{\"applicationDeadline\":\"2026-10-06 18:00\"}");
        when(analyses.findAllByDocumentVersionIdIn(List.of(2L))).thenReturn(List.of(analysis));
        var good = mock(DocumentAttachment.class);
        when(good.getFileName()).thenReturn("신청서.hwp");
        when(good.getDownloadUrl()).thenReturn("https://example.go.kr/file?id=1&part=2");
        when(good.getParseStatus()).thenReturn(AttachmentParseStatus.COMPLETED);
        when(good.getExtractedText()).thenReturn("제출서류: 신청서");
        var failed = mock(DocumentAttachment.class);
        when(failed.getFileName()).thenReturn("파싱 실패.pdf");
        when(failed.getDownloadUrl()).thenReturn("https://example.go.kr/file?id=2");
        when(failed.getParseStatus()).thenReturn(AttachmentParseStatus.FAILED);
        when(attachments.findAllByDocumentVersionId(2L)).thenReturn(List.of(good, failed));
        var request = new ReportPreparationService(runs, detections, analyses, reports, new ObjectMapper(), attachments, tasks, java.time.Clock.systemUTC())
                .prepare(10L).orElseThrow().documents().getFirst();
        var proposal = new ObjectMapper().readTree(request.proposal().toString());
        assertThat(proposal.path("preparation").path("submissionDocuments").size()).isEqualTo(2);
        assertThat(proposal.path("preparation").path("submissionDocuments").get(0).path("title").asText())
                .isEqualTo("국문 연구개발계획서");
        assertThat(proposal.path("preparation").path("submissionDocuments").get(1).path("title").asText())
                .isEqualTo("사업자등록증 사본");
        assertThat(proposal.path("preparation").has("companyInputs")).isFalse();
        var savedTask = org.mockito.ArgumentCaptor.forClass(com.publicmonitor.backend.domain.report.entity.ReportTask.class);
        verify(tasks).save(savedTask.capture());
        assertThat(savedTask.getValue().getReport().getSummary()).contains("기본 정보로 작성한 보고서");
        assertThat(savedTask.getValue().getRequestJson()).contains("신청서.hwp");
        assertThat(request.contentText()).isEqualTo("제출처: 온라인 신청 시스템");
        assertThat(request.comparisonSummary().toString()).contains("2026-10-06 18:00");
        assertThat(request.attachments()).hasSize(2);
        assertThat(request.attachments().getFirst().downloadUrl()).isEqualTo("https://example.go.kr/file?id=1&part=2");
        assertThat(request.attachments().getFirst().extractedText()).isEqualTo("제출서류: 신청서");
        assertThat(request.attachments().get(1).extractedText()).isNull();
        assertThat(request.attachments().get(1).downloadUrl()).endsWith("id=2");
    }
}
