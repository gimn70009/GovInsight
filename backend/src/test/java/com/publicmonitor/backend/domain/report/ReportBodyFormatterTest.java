package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ReportBodyFormatterTest {
    @Test void emailLinksCompletePlainUrlsWithoutChangingOtherChannels() {
        String body = "접수: www.kiat.or.kr 및 https://example.org/apply?x=1&y=%28form%29#submit";
        assertThat(ReportBodyFormatter.emailHtml(body)).isEqualTo(
                "접수: <a href=\"https://www.kiat.or.kr\">www.kiat.or.kr</a> 및 "
                + "<a href=\"https://example.org/apply?x=1&amp;y=%28form%29#submit\">"
                + "https://example.org/apply?x=1&amp;y=%28form%29#submit</a>");
        String query = "https://example.org/apply?token=abc;";
        assertThat(ReportBodyFormatter.emailHtml(query))
                .isEqualTo("<a href=\"" + query + "\">" + query + "</a>");
        assertThat(ReportBodyFormatter.html(body)).isEqualTo(body.replace("&", "&amp;"));
        assertThat(ReportBodyFormatter.displayLength(body)).isEqualTo(body.length());
    }

    @Test void emailKeepsProsePunctuationOutsideLinksAndBalancedUrlParenthesesInside() {
        assertThat(ReportBodyFormatter.emailHtml("홈페이지(www.kiat.or.kr)에서 접수. [https://example.org/a(b)]."))
                .isEqualTo("홈페이지(<a href=\"https://www.kiat.or.kr\">www.kiat.or.kr</a>)에서 접수. "
                        + "[<a href=\"https://example.org/a(b)\">https://example.org/a(b)</a>].");
        assertThat(ReportBodyFormatter.emailHtml("접수: HTTPS://example.org/apply."))
                .contains("href=\"HTTPS://example.org/apply\">HTTPS://example.org/apply</a>.");
    }

    @Test void emailDoesNotNestExistingLinksOrLinkUnsafeAddresses() {
        String body = "[www.example.org](https://example.org/form?a=1&b=2)";
        assertThat(ReportBodyFormatter.emailHtml(body)).isEqualTo(ReportBodyFormatter.html(body));
        assertThat(ReportBodyFormatter.emailHtml("<script> https://user:pass@example.org/x "
                + "https://example.org\\evil www. javascript:alert(1) help@www.example.org"))
                .doesNotContain("<a ", "<script>")
                .contains("&lt;script&gt;", "help@www.example.org");
    }

    @Test void rendersDateRangesAndCountsTheExpandedMessageLength() {
        String saved = "권역별 설명회(’26.10.13~10.14)";
        String displayed = "권역별 설명회(2026년 10월 13일~10월 14일)";
        assertThat(ReportBodyFormatter.html(saved)).isEqualTo(displayed);
        assertThat(ReportBodyFormatter.telegramHtml(saved)).isEqualTo(displayed);
        assertThat(ReportEmailTemplate.render("보고서", saved)).contains(displayed);
        assertThat(ReportBodyFormatter.displayLength(saved)).isEqualTo(displayed.length());
    }

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

    @Test void preservesExplicitCenturyContextWhenRenderingSeparateLines() {
        String saved = "1923년 자료\n• 일정: ’23.3월";
        assertThat(ReportBodyFormatter.telegramHtml(saved)).contains("1923년 3월").doesNotContain("2023년");
        assertThat(ReportEmailTemplate.render("자료", saved)).contains("1923년 3월").doesNotContain("2023년");
    }

    @Test void reportReadsAndRenderingExpandTheSameDatesWithoutChangingDownloadNames() {
        String saved = "▸ ’27년 사업\n• 일정: ’27.3월 제출\n• 기준: (’23~’25)\n"
                + "😀 [’27년 서식](https://example.org/'27년.pdf)";
        String expected = "▸ 2027년 사업\n• 일정: 2027년 3월 제출\n• 기준: (2023~2025)\n"
                + "😀 [’27년 서식](https://example.org/'27년.pdf)";
        var detail = new com.publicmonitor.backend.domain.report.web.dto.ReportDeliveryDetailResponse(
                null, saved, java.util.List.of(), java.util.List.of());
        var legacy = new com.publicmonitor.backend.domain.telegram.web.dto.TelegramReportDetailResponse(
                null, saved, java.util.List.of());
        assertThat(detail.body()).isEqualTo(expected);
        assertThat(legacy.body()).isEqualTo(expected);
        assertThat(ReportBodyFormatter.html(saved)).contains("2027년 3월 제출", "(2023~2025)", ">’27년 서식</a>");
        assertThat(ReportBodyFormatter.telegramHtml(saved))
                .contains("<b>▸ 2027년 사업</b>", "<b>• 일정:</b> 2027년 3월 제출", "(2023~2025)");
        assertThat(ReportEmailTemplate.render("보고서", saved)).contains("2027년 3월 제출", "(2023~2025)");
        String visible = "▸ 2027년 사업\n• 일정: 2027년 3월 제출\n• 기준: (2023~2025)\n😀 ’27년 서식";
        assertThat(ReportBodyFormatter.displayLength(saved)).isEqualTo(visible.length());
        assertThat(saved).contains("’27.3월 제출");
    }
}
