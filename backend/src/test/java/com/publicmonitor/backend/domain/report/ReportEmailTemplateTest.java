package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ReportEmailTemplateTest {
    @Test void stylesTitlesFactsAndAttachmentsWithoutChangingTheirValues() {
        String body = "신규 1건 │ 수정 0건 │ 변경 없음 0건\n\n────────────────\n\n"
                + "▸ 참여기업 모집\n기관: 예시 기관\n문서 유형: 사업 공고 │ 신규 │ 기회점수: 72점\n\n"
                + "요약: 지원사업 요약입니다.\n\n• 제출·의견 기한: 2026-11-06 16:00\n"
                + "• 제출처·방법: 원문 확인 필요\n\n첨부파일 ↓\n• [신청서.hwp](https://example.org/file?a=1&b=2)\n"
                + "원문: [게시글 보기](https://example.org/notice)";
        String html = ReportEmailTemplate.render("보고서", body);
        assertThat(html).contains("<h2 style=", "font-size:22px", "참여기업 모집</h2>", "기회점수: 72점", "제출 안내",
                "2026-11-06 16:00", "원문 확인 필요", "파일명을 눌러 다운로드", "href=\"https://example.org/file?a=1&amp;b=2\">신청서.hwp</a>");
        assertThat(html).doesNotContain("────────────────", "▸ 참여기업 모집", "문서 유형: ");
    }

    @Test void rendersMultipleCardsAndPreservesTrailingOmissionNotice() {
        String body = "▸ 첫 공고\n기관: 기관1\n\n요약: 첫 요약\n\n────────────────\n\n"
                + "▸ 둘째 공고\n기관: 기관2\n\n첨부파일 ↓\n• 등록된 첨부파일 없음\n"
                + "원문: 원문 확인 필요\n\n그 외 3건은 상세 화면에서 확인하세요.";
        String html = ReportEmailTemplate.render("보고서", body);
        assertThat(html).contains("공고 01", "공고 02", "첫 공고</h2>", "둘째 공고</h2>", "그 외 3건");
    }

    @Test void untrustedSourceHtmlAndUnsafeLinksCannotInjectMarkup() {
        String html = ReportEmailTemplate.render("<script>",
                "▸ <img src=x onerror=alert(1)>\n요약: <script>alert(1)</script>\n"
                + "첨부파일 ↓\n• [위험](javascript:alert(1))");
        assertThat(html).contains("&lt;script&gt;", "&lt;img", "javascript:alert(1)");
        assertThat(html).doesNotContain("<script>", "<img ", "href=\"javascript:");
    }

    @Test void legacyTextStillKeepsNewlinesAndLinks() {
        assertThat(ReportEmailTemplate.render("기존 보고서", "기존 본문\n[원문](https://example.org/notice)"))
                .contains("기존 본문<br>", "href=\"https://example.org/notice\">원문</a>");
    }
    @Test void rendersRealisticLongTitlesAndWritesLocalPreview() throws Exception {
        String body = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/reports/email-brief.txt"));
        String html = ReportEmailTemplate.render("[공공기관 모니터링] 9월 22일 보고서", body);
        assertThat(html).contains("광역연계형특구 후보과제 모집 공고</h2>", "기회점수: 65점");
        var output = java.nio.file.Path.of("build/report-preview/email-brief.html");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, html);
    }
}
