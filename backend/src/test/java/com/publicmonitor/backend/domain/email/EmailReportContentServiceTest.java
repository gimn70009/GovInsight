package com.publicmonitor.backend.domain.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.service.DocumentDetectionDetailService;
import com.publicmonitor.backend.domain.email.service.EmailReportContentService;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.report.BusinessSummaryEmailFixtures;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmailReportContentServiceTest {
    @Test
    void 저장_보고서의_오래된_문장_대신_같은_버전의_웹_상세를_사용한다() {
        var detections = mock(AnalysisDocumentDetectionRepository.class);
        var details = mock(DocumentDetectionDetailService.class);
        var report = mock(MonitoringReport.class);
        var run = mock(MonitoringRun.class);
        when(run.getId()).thenReturn(9L);
        when(report.getMonitoringRun()).thenReturn(run);
        when(report.getTitle()).thenReturn("보고서");
        var first = detection(1L, 10L);
        var repeated = detection(2L, 10L);
        when(detections.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(9L)).thenReturn(List.of(first, repeated));
        when(details.findSummaryById(1L)).thenReturn(BusinessSummaryEmailFixtures.jumpUp());

        var content = new EmailReportContentService(detections, details).build(report);

        assertThat(content.plainText()).contains("신규 1건  |  수정 0건  |  변경 없음 0건", "조사·컨설팅·전문지원", "2026-11-13 17:00");
        verify(details, times(1)).findSummaryById(1L);
        verify(details, never()).findSummaryById(2L);
        verify(report, never()).getSummary();
    }

    @Test
    void 공고를_점수순으로_정렬하고_같은_점수는_원래_순서를_보존한다() {
        var detections = mock(AnalysisDocumentDetectionRepository.class);
        var details = mock(DocumentDetectionDetailService.class);
        var report = mock(MonitoringReport.class);
        var run = mock(MonitoringRun.class);
        when(run.getId()).thenReturn(9L);
        when(report.getMonitoringRun()).thenReturn(run);
        when(report.getTitle()).thenReturn("보고서");
        var records = List.of(detection(1L, 10L), detection(2L, 11L), detection(3L, 12L), detection(4L, 13L));
        when(detections.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(9L)).thenReturn(records);
        when(details.findSummaryById(1L)).thenReturn(BusinessSummaryEmailFixtures.parse("{\"title\":\"미산정\"}"));
        when(details.findSummaryById(2L)).thenReturn(BusinessSummaryEmailFixtures.parse("{\"title\":\"첫번째\",\"analysis\":{\"opportunity\":{\"totalScore\":88}}}"));
        when(details.findSummaryById(3L)).thenReturn(BusinessSummaryEmailFixtures.parse("{\"title\":\"두번째\",\"analysis\":{\"opportunity\":{\"totalScore\":88}}}"));
        when(details.findSummaryById(4L)).thenReturn(BusinessSummaryEmailFixtures.parse("{\"title\":\"낮은점수\",\"analysis\":{\"opportunity\":{\"totalScore\":20}}}"));
        String text = new EmailReportContentService(detections, details).build(report).plainText();
        assertThat(text.indexOf("사업명: 첫번째")).isLessThan(text.indexOf("사업명: 두번째"));
        assertThat(text.indexOf("사업명: 두번째")).isLessThan(text.indexOf("사업명: 낮은점수"));
        assertThat(text.indexOf("사업명: 낮은점수")).isLessThan(text.indexOf("사업명: 미산정"));
    }

    @Test
    void 메일_렌더링은_실제_상세조회에서도_참여조건_작업을_시작하지_않는다() {
        var detections = mock(AnalysisDocumentDetectionRepository.class);
        var detailRepository = mock(com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository.class);
        var analyses = mock(com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository.class);
        var tasks = mock(com.publicmonitor.backend.domain.analysis.repository.AnalysisTaskRepository.class);
        var attachments = mock(com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository.class);
        var rewrites = mock(com.publicmonitor.backend.domain.document.service.ParticipationRewriteService.class);
        var details = new DocumentDetectionDetailService(detailRepository, analyses, tasks, attachments,
                new tools.jackson.databind.ObjectMapper(), rewrites);
        var report = mock(MonitoringReport.class);
        var run = mock(MonitoringRun.class);
        when(run.getId()).thenReturn(9L);
        when(report.getMonitoringRun()).thenReturn(run);
        when(report.getTitle()).thenReturn("보고서");
        var detection = mock(DocumentDetection.class, RETURNS_DEEP_STUBS);
        when(detection.getId()).thenReturn(1L);
        when(detection.getDocumentVersion().getId()).thenReturn(10L);
        when(detection.getDocumentVersion().getTitle()).thenReturn("중소기업 지원 공고");
        when(detection.getDocumentVersion().getContentText()).thenReturn("신청자격\n중소기업만 신청할 수 있습니다.");
        when(detection.getMonitoringRunSource().getMonitoringRun().getStatus())
                .thenReturn(com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus.COMPLETED);
        when(detections.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(9L)).thenReturn(List.of(detection));
        when(detailRepository.findById(1L)).thenReturn(java.util.Optional.of(detection));

        var content = new EmailReportContentService(detections, details).build(report);

        assertThat(content.plainText()).contains("중소기업 지원 공고");
        verifyNoInteractions(rewrites);
        details.findById(1L);
        assertThat(org.mockito.Mockito.mockingDetails(rewrites).getInvocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.getMethod().getName()).isEqualTo("resolve");
            assertThat(invocation.getArguments()[0]).isSameAs(detection.getDocumentVersion());
        });
    }
    private DocumentDetection detection(Long id, Long versionId) {
        var detection = mock(DocumentDetection.class);
        var version = mock(DocumentVersion.class);
        when(detection.getId()).thenReturn(id);
        when(detection.getDocumentVersion()).thenReturn(version);
        when(version.getId()).thenReturn(versionId);
        return detection;
    }
}
