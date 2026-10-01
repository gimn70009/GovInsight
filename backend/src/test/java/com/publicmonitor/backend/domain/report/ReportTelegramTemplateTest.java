package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ReportTelegramTemplateTest {
    private String card(String title, String summary, String url) {
        return "▸ " + title + "\n기관: 기관\n요약: " + summary
                + "\n• 제출·의견 기한: 2026년 10월 31일 24:00까지"
                + "\n원문: [게시글 보기](" + url + ")";
    }

    @Test void shortReportsKeepEverySummarySentence() {
        String body = card("공고", "첫 문장입니다. 두 번째 문장입니다.", "https://example.org/notice");
        assertThat(ReportTelegramTemplate.render("보고서", body)).isEqualTo("보고서\n\n" + body);
    }

    @Test void longSummariesAreProjectedOnlyForTelegramAndKeepActionFields() {
        String summary = "지원 조건을 안내합니다. ".repeat(400);
        String body = "신규 1건\n\n" + card("공고", summary, "https://example.org/notice?q=1&x=2");
        String telegram = ReportTelegramTemplate.render("보고서", body);
        assertThat(telegram).contains("요약 전문은 이메일 보고서", "2026년 10월 31일 24:00까지",
                "[게시글 보기](https://example.org/notice?q=1&x=2)");
        assertThat(ReportBodyFormatter.displayLength(telegram)).isLessThanOrEqualTo(4096);
        assertThat(ReportEmailTemplate.render("보고서", body)).contains(summary.strip());
    }

    @Test void wholeCardsAreOmittedWithoutSplittingLongUrlsOrSurrogates() {
        String url = "https://example.org/notice?key=" + "x".repeat(1800);
        String body = "신규 100건\n\n" + (card("😀공고", "요약입니다.", url) + "\n\n").repeat(100);
        String telegram = ReportTelegramTemplate.render("긴 제목😀".repeat(40), body);
        assertThat(telegram).contains("그 외", "](https://example.org/notice?key=" + "x".repeat(1800) + ")");
        assertThat(ReportBodyFormatter.displayLength(telegram)).isLessThanOrEqualTo(4096);
        assertThat(telegram).doesNotContain("�");
    }

    @Test void longLinkAddressesDoNotConsumeVisibleMessageBudget() {
        String body = card("공고", "완전한 요약입니다.", "https://example.org/?x=" + "a".repeat(6000));
        assertThat(ReportTelegramTemplate.render("보고서", body)).isEqualTo("보고서\n\n" + body);
    }

    @Test void oversizedUnstructuredLegacyReportsStaySubjectToExistingRejection() {
        String body = "본문".repeat(3000);
        assertThat(ReportTelegramTemplate.render("보고서", body)).isEqualTo("보고서\n\n" + body);
    }
}
