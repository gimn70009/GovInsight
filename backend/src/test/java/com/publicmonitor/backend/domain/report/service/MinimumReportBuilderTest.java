package com.publicmonitor.backend.domain.report.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class MinimumReportBuilderTest {
    @Test void missingAnalysisAndUnsafeLinksStillProduceBoundedPlainMinimum() {
        var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, LocalDateTime.now());
        var notice = mock(DocumentDetection.class, RETURNS_DEEP_STUBS);
        when(notice.getDocumentVersion().getTitle()).thenReturn("[제목]\n" + "긴 제목".repeat(100));
        when(notice.getDocument().getOriginalUrl()).thenReturn("javascript:alert(1)");
        when(notice.getMonitoringRunSource().getMonitoringSource().getOrganizationName()).thenReturn("기관명");
        var draft = MinimumReportBuilder.build(run, Collections.nCopies(50, notice), Map.of());
        assertThat(draft.summary()).contains("분석 결과를 확인하지 못했습니다", "기관명", "그 외").doesNotContain("javascript:");
        assertThat(draft.title().length() + draft.summary().length()).isLessThan(3900);
    }
    @Test void emptyRunProducesANoticeInsteadOfFailing() {
        var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, LocalDateTime.now());
        assertThat(MinimumReportBuilder.build(run, List.of(), Map.of()).summary()).contains("게시글이 없습니다");
    }
}
