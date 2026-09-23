package com.publicmonitor.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ReportAttachmentExcerptsTest {
    @Test void lateShortNoticeSurvivesLargeEarlierArchives() {
        var inputs = new ArrayList<DocumentAttachment>();
        for (int i = 0; i < 5; i++) {
            var attachment = mock(DocumentAttachment.class);
            when(attachment.getFileName()).thenReturn("자료" + i + ".zip");
            when(attachment.getDownloadUrl()).thenReturn("https://example.go.kr/" + i);
            when(attachment.getParseStatus()).thenReturn(AttachmentParseStatus.COMPLETED);
            when(attachment.getExtractedText()).thenReturn("긴 참고자료".repeat(15_000));
            inputs.add(attachment);
        }
        var notice = mock(DocumentAttachment.class);
        when(notice.getFileName()).thenReturn("국문 접수 안내문.pdf");
        when(notice.getDownloadUrl()).thenReturn("https://example.go.kr/notice");
        when(notice.getParseStatus()).thenReturn(AttachmentParseStatus.COMPLETED);
        when(notice.getExtractedText()).thenReturn("접수: ~2027. 1. 28. 16:00까지\n제출처: K-PASS");
        inputs.add(notice);
        var result = ReportAttachmentExcerpts.build(inputs);
        assertThat(result.getLast().extractedText()).isEqualTo(notice.getExtractedText());
        assertThat(result.getLast().downloadUrl()).isEqualTo("https://example.go.kr/notice");
        assertThat(result.stream().mapToInt(a -> a.extractedText().length()).sum()).isLessThanOrEqualTo(120_000);
        assertThat(result).allSatisfy(a -> assertThat(a.extractedText().length()).isLessThanOrEqualTo(40_000));
        assertThat(result.getFirst().extractedText()).contains("일부 원문 생략");
    }
}
