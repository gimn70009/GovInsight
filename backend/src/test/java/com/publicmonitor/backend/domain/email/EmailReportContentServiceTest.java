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
        when(details.findById(1L)).thenReturn(BusinessSummaryEmailFixtures.jumpUp());

        var content = new EmailReportContentService(detections, details).build(report);

        assertThat(content.plainText()).contains("신규 1건  |  수정 0건  |  변경 없음 0건", "조사·컨설팅·전문지원", "2026-11-13 17:00");
        verify(details, times(1)).findById(1L);
        verify(details, never()).findById(2L);
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
        when(details.findById(1L)).thenReturn(BusinessSummaryEmailFixtures.parse("{\"title\":\"미산정\"}"));
        when(details.findById(2L)).thenReturn(BusinessSummaryEmailFixtures.parse("{\"title\":\"첫번째\",\"analysis\":{\"opportunity\":{\"totalScore\":88}}}"));
        when(details.findById(3L)).thenReturn(BusinessSummaryEmailFixtures.parse("{\"title\":\"두번째\",\"analysis\":{\"opportunity\":{\"totalScore\":88}}}"));
        when(details.findById(4L)).thenReturn(BusinessSummaryEmailFixtures.parse("{\"title\":\"낮은점수\",\"analysis\":{\"opportunity\":{\"totalScore\":20}}}"));
        String text = new EmailReportContentService(detections, details).build(report).plainText();
        assertThat(text.indexOf("사업명: 첫번째")).isLessThan(text.indexOf("사업명: 두번째"));
        assertThat(text.indexOf("사업명: 두번째")).isLessThan(text.indexOf("사업명: 낮은점수"));
        assertThat(text.indexOf("사업명: 낮은점수")).isLessThan(text.indexOf("사업명: 미산정"));
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
