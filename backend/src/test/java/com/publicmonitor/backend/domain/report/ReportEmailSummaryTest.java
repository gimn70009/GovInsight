package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import java.util.List;

class ReportEmailSummaryTest {
    @Test void selectsTwoCompleteSentencesAndLeavesSavedSummaryIntact() {
        String value = "일본 공동연구를 지원합니다. 연 5억원을 최대 3년 지원합니다. "
                + "영문과 국문 계획서를 모두 제출해야 합니다. 평가는 별도로 진행합니다.";
        assertThat(ReportEmailSummary.lines(value)).containsExactly(
                "일본 공동연구를 지원합니다.", "연 5억원을 최대 3년 지원합니다.");
        assertThat(value).endsWith("평가는 별도로 진행합니다.");
    }

    @Test void keepsDatesDecimalsNamesAndUrlsWithinTheirSentences() {
        String first = "1.5억원을 지원하며 2026.10.26까지 https://example.org/apply?q=1.5 에 접수합니다.";
        assertThat(ReportEmailSummary.lines(first + " 세부 기준은 원문에 있습니다. 심사는 이후 진행합니다."))
                .containsExactly(first, "세부 기준은 원문에 있습니다.");
    }

    @Test void doesNotAddAnotherLongSentenceOrCutTheFirstMidway() {
        String first = "지원 대상과 조건 ".repeat(34) + "입니다.";
        assertThat(ReportEmailSummary.lines(first + " 추가 절차는 따로 안내합니다."))
                .containsExactly(first);
        assertThat(ReportEmailSummary.lines("핵심 내용입니다. " + "추가 조건 ".repeat(60) + "입니다."))
                .containsExactly("핵심 내용입니다.");
    }

    @Test void emptySummaryStaysEmptyAndBrokenTableStillUsesSourceGuidance() {
        assertThat(ReportEmailSummary.lines(" ")).isEmpty();
        assertThat(ReportEmailSummary.lines("포상대상 훈격 규모 수공기간 6 계 8"))
                .containsExactly("원문 표에서 사업 내용과 세부 조건을 확인해 주세요.");
    }
    @Test void publicationYearAndSupportDurationAreNotApplicationDeadlines() {
        String summary = "이 공고는 2026년 유공자에게 장관표창을 수여하는 추천·신청 공고입니다. "
                + "2026년 신청 기업에는 최대 1.5억원을 지원합니다. 신청 마감은 2026년 10월 26일입니다.";
        assertThat(ReportEmailSummary.lines(summary, true)).containsExactly(
                "이 공고는 2026년 유공자에게 장관표창을 수여하는 추천·신청 공고입니다.",
                "2026년 신청 기업에는 최대 1.5억원을 지원합니다.");
    }
    @Test void datesWithKoreanParticlesAreRemovedWithoutCuttingNumbers() {
        for (String sentence : List.of("신청기간은 2026.10.7부터 2026.10.26까지입니다.",
                "접수 마감은 2026-10-26까지입니다.", "접수 마감은 10/26입니다.")) {
            assertThat(ReportEmailSummary.lines(sentence, true)).isEmpty();
        }
    }
    @Test void applicantEstablishmentDateIsNotAClosingDate() {
        String condition = "신청기업은 2025년 1월 1일 이전 설립한 법인이어야 합니다.";
        assertThat(ReportEmailSummary.lines(condition + " 2026년 10월 26일까지 온라인으로 접수합니다.", true))
                .containsExactly(condition);
    }
}
