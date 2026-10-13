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

    @Test void newScheduleLabelsRemainSeparateThroughTelegramFormatting() {
        String body = "▸ 공고\n• 공고일: 2026년 10월 6일\n• 관련 일정: 설명회 2026년 10월 20일\n"
                + "• 제출·의견 기한: 원문 확인 필요";
        String html = ReportBodyFormatter.telegramHtml(ReportTelegramTemplate.render("보고서", body));
        assertThat(html).contains("<b>• 공고일:</b> 2026년 10월 6일", "<b>• 관련 일정:</b> 설명회 2026년 10월 20일",
                "<b>• 제출·의견 기한:</b> 원문 확인 필요");
    }

    @Test void longSummariesUseChannelSpecificDisplayAndKeepActionFields() {
        String summary = "지원 조건을 안내합니다. ".repeat(400);
        String body = "신규 1건\n\n" + card("공고", summary, "https://example.org/notice?q=1&x=2");
        String telegram = ReportTelegramTemplate.render("보고서", body);
        assertThat(telegram).contains("요약 전문은 게시글 상세", "2026년 10월 31일 24:00까지",
                "[게시글 보기](https://example.org/notice?q=1&x=2)");
        assertThat(ReportBodyFormatter.displayLength(telegram)).isLessThanOrEqualTo(4096);
        String email = ReportEmailTemplate.render("보고서", body);
        String visibleSummary = java.util.regex.Pattern.compile(
                "<p data-report-summary-item[^>]*>(.*?)</p>").matcher(email).results()
                .map(match -> match.group(1)).collect(java.util.stream.Collectors.joining(" "));
        assertThat(visibleSummary).isEqualTo("지원 조건을 안내합니다. 지원 조건을 안내합니다.");
        assertThat(body).contains(summary);
    }

    @Test void longReportsKeepBusinessFactsWhenProjectingTheirSummary() {
        String metadata = "\n공고 성격: 사업 기회\n사업 유형: R&D·기술개발\n사업 방식: 연구개발비 지원";
        String facts = "\n• 금액·규모: 과제당 연 최대 5억 원, 최대 3년\n  ↳ 자부담 25% 이상"
                + "\n• 참여 역할: 국내 주관기관\n• 제출 주체·대상: 기업부설연구소 보유 필수"
                + "\n  ↳ 대기업 단독 신청 불가";
        String body = "신규 1건\n\n" + card("공고", "지원 조건을 안내합니다. ".repeat(400),
                "https://example.org/notice?q=1&x=2")
                .replace("\n요약:", metadata + "\n요약:")
                .replace("\n원문:", facts + "\n원문:");
        String telegram = ReportTelegramTemplate.render("보고서", body);
        assertThat(telegram).contains(metadata, facts, "요약 전문은 게시글 상세",
                "[게시글 보기](https://example.org/notice?q=1&x=2)");
        assertThat(ReportBodyFormatter.displayLength(telegram)).isLessThanOrEqualTo(4096);
        assertThat(body).doesNotContain("요약 전문은 게시글 상세");
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
