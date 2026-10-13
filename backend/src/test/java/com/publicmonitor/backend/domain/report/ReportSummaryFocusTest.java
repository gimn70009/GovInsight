package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportSummaryFocusTest {
    private static final String PURPOSE = "해외 연구기관과 공동으로 연구할 국내 기업을 모집합니다.";
    private static ReportSchedulePresentation.Fact fact(String label, String value, boolean deadline) {
        return new ReportSchedulePresentation.Fact(label, value, deadline);
    }

    @Test void duplicateAmountLeavesPurposeButDifferentAnnualScopeIsPreserved() {
        String detail = "과제당 연 최대 5억 원을 지원합니다.";
        var amount = List.of(fact("금액·규모", detail, false));
        assertThat(ReportEmailSummary.lines(PURPOSE + " " + detail, false, amount, List.of())).containsExactly(PURPOSE);
        var total = List.of(fact("금액·규모", "과제당 총 최대 5억 원을 지원합니다.", false));
        assertThat(ReportEmailSummary.lines(PURPOSE + " " + detail, false, total, List.of())).containsExactly(PURPOSE, detail);
    }

    @Test void detailOnlySummaryIsNotReplacedWithAnInventedPurpose() {
        String detail = "과제당 연 최대 5억 원을 지원합니다.";
        assertThat(ReportEmailSummary.lines(detail, false, List.of(fact("금액·규모", detail, false)), List.of()))
                .containsExactly(detail);
    }

    @Test void policyChangeAndEligibilityRemainEvenWhenNumbersRepeat() {
        for (String value : List.of("지원한도는 3억 원에서 5억 원으로 확대됩니다.",
                "신청 기간은 동일하지만 반드시 두 국가에 모두 접수해야 합니다.")) {
            assertThat(ReportEmailSummary.lines(PURPOSE + " " + value, false,
                    List.of(fact("금액·규모", value, false)), List.of(fact("제출처·방법", value, false))))
                    .containsExactly(PURPOSE, value);
        }
    }

    @Test void differentCountryDeadlinesCannotBeCombinedToMatchADisputedDate() {
        String value = "영문 접수 마감은 2027년 1월 21일입니다.";
        var schedule = List.of(fact("제출·의견 기한", "영문 접수 마감: 2027년 1월 20일\n국문 접수 마감: 2027년 1월 21일", true));
        assertThat(ReportEmailSummary.lines(PURPOSE + " " + value, false, List.of(), schedule)).containsExactly(PURPOSE, value);
    }

    @Test void routeCountryCannotSupplyTheMissingCountryOfADifferentDeadline() {
        String value = "영문 접수 기한은 2027년 1월 21일까지이며 온라인으로 접수합니다.";
        var schedule = List.of(fact("제출·의견 기한", "영문 접수 기한: 2027년 1월 20일\n국문 접수 기한: 2027년 1월 21일", true),
                fact("제출처·방법", "영문 온라인 접수", false));
        assertThat(ReportEmailSummary.lines(PURPOSE + " " + value, false, List.of(), schedule)).containsExactly(PURPOSE, value);
    }

    @Test void openingDateIsNotTreatedAsTheSameDayClosingDate() {
        String value = "접수는 2026년 10월 1일부터입니다.";
        assertThat(ReportEmailSummary.lines(PURPOSE + " " + value, false, List.of(),
                List.of(fact("제출·의견 기한", "접수 마감: 2026년 10월 1일", true)))).containsExactly(PURPOSE, value);
    }

    @Test void sameModeDoesNotMakeDifferentSubmissionSystemsEquivalent() {
        String value = "접수는 K-PASS 온라인으로 진행합니다.";
        assertThat(ReportEmailSummary.lines(PURPOSE + " " + value, false, List.of(),
                List.of(fact("제출처·방법", "IRIS 온라인 접수", false)))).containsExactly(PURPOSE, value);
        assertThat(ReportEmailSummary.lines(PURPOSE + " " + value, false, List.of(),
                List.of(fact("제출처·방법", "K-PASS 온라인 접수", false)))).containsExactly(PURPOSE);
    }

    @Test void alternativeAndMandatorySequentialRoutesAreNotInterchangeable() {
        String value = "의견 제출 기한은 2026년 10월 18일까지이며 우편 또는 전자우편으로 접수합니다.";
        var schedule = List.of(fact("제출·의견 기한", "의견 제출 기한: 2026년 10월 18일", true),
                fact("제출처·방법", "우편 접수 후 이메일 제출", false));
        assertThat(ReportEmailSummary.lines(PURPOSE + " " + value, false, List.of(), schedule)).containsExactly(PURPOSE, value);
    }
}
