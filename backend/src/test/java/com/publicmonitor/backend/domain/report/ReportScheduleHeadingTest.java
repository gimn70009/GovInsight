package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportScheduleHeadingTest {
    private static final String LABEL = "제출·의견 기한";
    private static final String ENGLISH = "영문 연구개발계획서 접수 마감: 2027년 1월 20일(수)";
    private static final String KOREAN = "국문 연구개발계획서 접수 마감: 2027년 1월 21일(목) 16:00(KST)";

    @ParameterizedTest
    @ValueSource(strings = {"\n", " / ", " | ", "; ", " "})
    void languageAndDocumentHeadingsStayWithTheirOwnDates(String separator) {
        assertThat(ReportSchedulePresentation.facts(LABEL, ENGLISH + separator + KOREAN))
                .containsExactly(new ReportSchedulePresentation.Fact(LABEL, ENGLISH, true),
                        new ReportSchedulePresentation.Fact(LABEL, KOREAN, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "국내 1차 연구개발계획서", "제2차 국외 연구개발계획서", "스페인 연구개발계획서",
            "국문 공동 연구 개발 계획서", "한국 영문 연구개발계획서"
    })
    void completePhysicalHeadingDoesNotProduceAnOrphanRelatedSchedule(String prefix) {
        String value = prefix + " 접수 마감: 2027년 1월 21일 16:00(KST)";
        assertThat(ReportSchedulePresentation.facts(LABEL, value))
                .containsExactly(new ReportSchedulePresentation.Fact(LABEL, value, true));
    }

    @Test void nextHeadingCannotConsumeThePreviousRowsReceiptCondition() {
        String english = ENGLISH + ". 우편은 도착분 기준";
        assertThat(ReportSchedulePresentation.facts(LABEL, english + "\n" + KOREAN))
                .containsExactly(new ReportSchedulePresentation.Fact(LABEL, english, true),
                        new ReportSchedulePresentation.Fact(LABEL, KOREAN, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", " / ", " | ", "; "})
    void explicitSeparatorsPreserveCompoundCountryAndLanguageCaptions(String separator) {
        String overseas = "스페인 영문 공동 연구 개발 계획서 접수 마감: 2027년 1월 22일 16:00";
        assertThat(ReportSchedulePresentation.facts(LABEL, KOREAN + separator + overseas))
                .containsExactly(new ReportSchedulePresentation.Fact(LABEL, KOREAN, true),
                        new ReportSchedulePresentation.Fact(LABEL, overseas, true));
    }

    @Test void groupedFactsKeepTwoCompleteRowsWithoutLanguageOnlyFacts() {
        assertThat(ReportSchedulePresentation.groupedFacts(LABEL, ENGLISH + "\n" + KOREAN))
                .containsExactly(new ReportSchedulePresentation.Fact(LABEL, ENGLISH + "\n" + KOREAN, true));
    }

    @Test void emailContinuationRowsPreserveBothLanguageHeadingsAndTimezone() {
        String html = ReportEmailTemplate.render("보고서", "▸ 국제 공동개발 공고\n• " + LABEL + ": "
                + ENGLISH + "\n  ↳ " + KOREAN);
        assertThat(html).contains(ENGLISH, KOREAN).doesNotContain("data-report-fact=\"관련 일정\"");
    }

    @Test void telegramRetainsLanguageHeadingsInBothDirectAndProjectedReports() {
        String body = "▸ 국제 공동개발 공고\n요약: " + "공개 사업 안내. ".repeat(500)
                + "\n• " + LABEL + ": " + ENGLISH + "\n  ↳ " + KOREAN;
        assertThat(ReportTelegramTemplate.render("보고서", body)).contains(ENGLISH, KOREAN);
        assertThat(ReportTelegramTemplate.render("보고서", "▸ 공고\n• " + LABEL + ": "
                + ENGLISH + "\n  ↳ " + KOREAN)).contains(ENGLISH, KOREAN);
    }
}
