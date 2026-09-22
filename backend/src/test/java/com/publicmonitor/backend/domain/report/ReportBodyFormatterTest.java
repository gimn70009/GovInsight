package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ReportBodyFormatterTest {
    @Test void linksKeepNamesAndQueryStringsWhileEscapingSourceHtml() {
        String body = "<script>\n• [신청서 & 서식](https://example.go.kr/f?id=1&x=2)";
        assertThat(ReportBodyFormatter.html(body)).isEqualTo("&lt;script&gt;\n• <a href=\"https://example.go.kr/f?id=1&amp;x=2\">신청서 &amp; 서식</a>");
        assertThat(ReportBodyFormatter.displayLength(body)).isEqualTo("<script>\n• 신청서 & 서식".length());
    }
    @Test void unsafeLinksAreNeverTurnedIntoAnchors() {
        String body = "[x](javascript:alert(1)) [x](https://user:pass@example.org/f)";
        assertThat(ReportBodyFormatter.html(body)).doesNotContain("<a ");
        assertThat(ReportBodyFormatter.displayLength(body)).isEqualTo(body.length());
    }
    @Test void longUrlDoesNotConsumeVisibleMessageBudgetAndEmojiUsesUtf16() {
        String body = "😀 [다운로드](https://example.org/f?token=" + "a".repeat(5000) + ")";
        assertThat(ReportBodyFormatter.displayLength(body)).isEqualTo(7);
    }
    @Test void telegramEmphasizesTitlesAndLabelsWithoutTrustingSourceMarkup() {
        assertThat(ReportBodyFormatter.telegramHtml("▸ <제목>\n• 제출 서류: 신청서\n첨부파일 ↓\n• [서식](https://example.org/a)"))
                .isEqualTo("<b>▸ &lt;제목&gt;</b>\n<b>• 제출 서류:</b> 신청서\n<b>첨부파일 ↓</b>\n• <a href=\"https://example.org/a\">서식</a>");
    }
    @Test void telegramKeepsChecklistRolesAndArchiveLink() {
        String body = "제출 준비 서류 ↓\n◆ 조건부 │ 납세증명서\n제출 주체: 해당 기관\n"
                + "준비 담당(권장): 재무 담당\n양식: [ZIP 다운로드](https://example.org/zip?id=1&seq=2)\n"
                + "ZIP 내부 파일: 서식/납세증명서.pdf";
        assertThat(ReportBodyFormatter.telegramHtml(body)).contains("<b>제출 준비 서류 ↓</b>",
                "<b>◆ 조건부 │ 납세증명서</b>", "준비 담당(권장): 재무 담당", "ZIP 내부 파일: 서식/납세증명서.pdf",
                "href=\"https://example.org/zip?id=1&amp;seq=2\"");
    }
}
