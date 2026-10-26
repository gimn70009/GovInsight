package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportRoleReadabilityTest {
    private static final String OPTIONS = "□ 대학/출연(연)/국공립연/특정연 □ 기타 비영리법인(병원 등) □ 외국법인";
    private static final String CLEAR = "주관기관유형□ 제한없음 ■ 기업 " + OPTIONS
            + " 주관기관 외 필수참여기관■ 제한없음 □ 기업 " + OPTIONS;
    private static final String UNCLEAR = "주관기관유형□ 제한없음 □ 기업 " + OPTIONS
            + " 주관기관 외 필수참여기관□ 제한없음 □ 기업 " + OPTIONS;

    @Test void resolvesSelectedInstitutionsUnderTheirRolesWithoutUncheckedOptions() {
        assertThat(ReportFactPresentation.lines("제출 주체·대상", CLEAR))
                .containsExactly("주관기관: 기업", "주관기관 외 필수 참여기관: 제한 없음");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "주관기관 □ 제한없음 □ 기업 □ 대학",
            "주관기관 ■ 제한없음 ■ 기업",
            "주관기관 ■ 제한없음 ■ 기업 □ 대학",
            "주관기관 □ 기업 ■ 기업",
            "주관기관 □ 기업 ■ 대학 □ 대학",
            "주관기관 □ 제한없음 ■ 알수없는기관",
            "주관기관 □ 기업 ■ 대학 □ 외국법인 추가 조건 누락 가능",
            "주관기관 □ 기업 ■ 대학 주관기관 □ 기업 ■ 대학"
    })
    void ambiguousSelectionsNeverGrantEligibility(String value) {
        assertThat(ReportFactPresentation.lines("제출 주체·대상", value))
                .containsExactly("주관기관: 원문 확인 필요");
    }

    @Test void uncertaintyIsScopedToTheAffectedInstitution() {
        assertThat(ReportFactPresentation.lines("제출 주체·대상",
                "주관기관 □ 대학 ■ 기업 공동기관 □ 대학 □ 기업"))
                .containsExactly("주관기관: 기업", "공동기관: 원문 확인 필요");
    }

    @Test void originalTwelveOptionsNeverGrantTheAmbiguousSecondRole() {
        String value = "주관기관유형□ 제한없음 □ 대학/출연(연)/국공립연/특정연 □ 기업 □ 기타 비영리법인(병원 등) "
                + "□ 외국법인주관기관 외 필수참여기관■ 제한없음 □ 기업 □ 기타 비영리법인(병원 등) "
                + "□ 외국법인 □ 제한없음 □ 기업 □ 기타 비영리법인(병원 등) □ 외국법인";
        assertThat(ReportFactPresentation.lines("제출 주체·대상", value))
                .containsExactly("주관기관: 원문 확인 필요", "주관기관 외 필수 참여기관: 원문 확인 필요");
        String html = ReportEmailTemplate.render("보고서", "▸ 기존 보고서\n• 제출 주체·대상: " + value);
        assertThat(html).doesNotContain("□", "■", "국공립연", "외국법인", "제한 없음");
    }

    @Test void mixedSelectedAndPlainInstitutionRowsPreserveTheirOwnConditions() {
        assertThat(ReportFactPresentation.lines("제출 주체·대상",
                "주관기관 □ 기업 ■ 대학 공동기관: 기업 (업력 3년 이상)"))
                .containsExactly("주관기관: 대학", "공동기관: 기업 (업력 3년 이상)");
    }

    @Test void overseasInstitutionNameStaysAttachedToItsSelection() {
        assertThat(ReportFactPresentation.lines("제출 주체·대상", "국외 주관기관 □ 기업 ■ 대학"))
                .containsExactly("국외 주관기관: 대학");
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n• 제출 주체·대상: 국외 주관기관: 대학");
        assertThat(html).contains("data-report-role", "국외 주관기관:");
    }

    @Test void multipleCheckedTypesAndSeparateInstitutionRequirementsRemainDistinct() {
        assertThat(ReportFactPresentation.lines("제출 주체·대상",
                "주관기관 □ 제한없음 ■ 중소기업 ■ 중견기업 공동기관 □ 제한없음 ■ 중소기업"))
                .containsExactly("주관기관: 중소기업, 중견기업", "공동기관: 중소기업");
    }

    @ParameterizedTest
    @ValueSource(strings = {"다만, 주관기관이 부도 상태인 경우 제외", "다만, 주관기관: 부도 기업 제외", "※ 공동기관은 중소기업 필수", "단, 업력 3년 이상만 가능"})
    void conditionsMentioningInstitutionsDoNotBecomeNewRows(String note) {
        assertThat(ReportFactPresentation.lines("제출 주체·대상", "주관기관 □ 기업 ■ 대학 " + note))
                .containsExactly("주관기관: 대학\n" + note);
    }

    @Test void duplicateRoleAndUnclearSelectionKeepEveryException() {
        String value = "주관기관 □ 기업 ■ 대학 단, 부도 기관은 제외 주관기관 □ 기업 ■ 대학 ※ 업력 3년 이상";
        assertThat(String.join("\n", ReportFactPresentation.lines("제출 주체·대상", value)))
                .contains("주관기관: 원문 확인 필요", "단, 부도 기관은 제외", "※ 업력 3년 이상");
    }

    @Test void ordinarySquareBulletsRetainEligibilityAndExclusions() {
        assertThat(ReportFactPresentation.lines("제출 주체·대상",
                "□ 신청 자격: 중소기업 ■ 업력 3년 이상. 단, 참여제한 기관은 제외합니다."))
                .containsExactly("중소기업", "업력 3년 이상. 단, 참여제한 기관은 제외합니다.");
    }

    @Test void cannotAssociateAdjacentRoleHeadersWithOneSetOfOptions() {
        assertThat(ReportFactPresentation.lines("제출 주체·대상", "주관기관 공동기관 □ 기업 ■ 대학"))
                .containsExactly("원문 확인 필요: 신청 기관의 역할과 선택 여부를 확인해 주세요.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"□ 기업 ■ 대학", "□ 지방자치단체 ■ 공공기관"})
    void institutionChoicesWithoutRoleAreUnconfirmed(String value) {
        assertThat(ReportFactPresentation.lines("제출 주체·대상", value))
                .containsExactly("원문 확인 필요: 신청 기관의 역할과 선택 여부를 확인해 주세요.");
    }

    @Test void institutionChoicesWithoutRoleStillKeepExclusions() {
        assertThat(ReportFactPresentation.lines("제출 주체·대상", "□ 기업 ■ 대학 단, 업력 3년 이상"))
                .containsExactly("원문 확인 필요: 신청 기관의 역할과 선택 여부를 확인해 주세요.\n단, 업력 3년 이상");
    }

    @Test void referenceDateInsideParenthesesRemainsWithDeadline() {
        String value = "접수 마감: 2026년 10월 31일(공고일: 2026년 10월 6일 기준)";
        assertThat(ReportSchedulePresentation.facts("제출·의견 기한", value))
                .containsExactly(new ReportSchedulePresentation.Fact("제출·의견 기한", value, true));
    }

    @Test void publicationDateIsSeparatedFromDeadlineWithSubmissionConditions() {
        var facts = ReportSchedulePresentation.facts("제출·의견 기한",
                "공고일: 2026년 10월 6일 / 접수 마감: 2026년 10월 30일 18:00. 우편은 도착분만 유효합니다.");
        assertThat(facts).containsExactly(
                new ReportSchedulePresentation.Fact("공고일", "2026년 10월 6일", false),
                new ReportSchedulePresentation.Fact("제출·의견 기한", "접수 마감: 2026년 10월 30일 18:00. 우편은 도착분만 유효합니다.", true));
    }

    @Test void unseparatedDateLabelsDoNotConsumeThePreviousDayUnit() {
        assertThat(ReportSchedulePresentation.facts("제출·의견 기한", "공고일: 2026년 10월 6일 접수 마감: 2026년 10월 30일"))
                .containsExactly(new ReportSchedulePresentation.Fact("공고일", "2026년 10월 6일", false),
                        new ReportSchedulePresentation.Fact("제출·의견 기한", "접수 마감: 2026년 10월 30일", true));
    }

    @Test void multipleCountriesShareOneDeadlineRowAndKeepSeparateValues() {
        String value = "국내 접수 마감: 2026년 10월 30일 18:00 / 해외 접수 마감: 2026년 11월 6일 16:00";
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n• 제출·의견 기한: " + value);
        assertThat(html.split("data-report-fact=\"제출·의견 기한\"", -1)).hasSize(2);
        assertThat(html).contains("국내 접수 마감: 2026년 10월 30일 18:00", "해외 접수 마감: 2026년 11월 6일 16:00");
        assertThat(html.split("data-report-fact-item", -1)).hasSize(3);
    }

    @Test void evaluationDateDoesNotInheritDeadlineEmphasis() {
        assertThat(ReportSchedulePresentation.facts("제출·의견 기한", "접수 마감: 2026년 10월 30일 / 평가: 2026년 11월 6일"))
                .containsExactly(new ReportSchedulePresentation.Fact("제출·의견 기한", "접수 마감: 2026년 10월 30일", true),
                        new ReportSchedulePresentation.Fact("관련 일정", "평가: 2026년 11월 6일", false));
    }

    @Test void applicationDueDateAfterPublicationKeepsItsOwnRole() {
        assertThat(ReportSchedulePresentation.facts("제출·의견 기한", "공고일: 2026년 10월 6일 / 신청 기한: 2026년 10월 30일"))
                .containsExactly(new ReportSchedulePresentation.Fact("공고일", "2026년 10월 6일", false),
                        new ReportSchedulePresentation.Fact("제출·의견 기한", "신청 기한: 2026년 10월 30일", true));
    }

    @Test void publicationOnlyCannotBecomeSubmissionDeadline() {
        assertThat(ReportSchedulePresentation.facts("제출·의견 기한", "공고일: 2026년 10월 6일"))
                .containsExactly(new ReportSchedulePresentation.Fact("공고일", "2026년 10월 6일", false),
                        new ReportSchedulePresentation.Fact("제출·의견 기한", "원문 확인 필요", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026년 10월 6일", "평가: 2026년 10월 6일", "접수 시작: 2026년 10월 6일", "설명회: 2026년 10월 6일"})
    void datesWithoutADeadlineRoleAreRelatedSchedules(String value) {
        assertThat(ReportSchedulePresentation.facts("제출·의견 기한", value))
                .containsExactly(new ReportSchedulePresentation.Fact("관련 일정", value, false),
                        new ReportSchedulePresentation.Fact("제출·의견 기한", "원문 확인 필요", false));
    }

    @Test void provisionalDeadlineDoesNotReceiveConfirmedDeadlineEmphasis() {
        String value = "접수 마감: 2026년 10월 31일 예정";
        assertThat(ReportSchedulePresentation.facts("제출·의견 기한", value))
                .containsExactly(new ReportSchedulePresentation.Fact("제출·의견 기한", value, false));
    }

    @Test void relativeDeadlineIsNotMisreadAsAPublicationDate() {
        assertThat(ReportSchedulePresentation.facts("제출·의견 기한", "공고일로부터 30일 이내 제출"))
                .containsExactly(new ReportSchedulePresentation.Fact("제출·의견 기한", "공고일로부터 30일 이내 제출", true));
    }

    @Test void htmlUsesRoleGroupsAndNeutralPublicationDateWhilePreservingSafeLinks() {
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n• 제출 주체·대상: "
                + "주관기관 □ 기업 ■ 대학 ※ <script>alert(1)</script> 공동기관은 중소기업 필수\n"
                + "• 공고일: 2026년 10월 6일\n• 관련 일정: 설명회 2026년 10월 20일\n"
                + "• 제출·의견 기한: 접수 마감: 2026년 10월 30일 18:00\n"
                + "• 제출처·방법: [접수](https://example.org/apply?a=1&b=2)");
        assertThat(html).contains("data-report-role", "font-size:16px;line-height:1.7", "&lt;script&gt;",
                "공동기관은 중소기업 필수", "href=\"https://example.org/apply?a=1&amp;b=2\"");
        assertThat(html).doesNotContain("<script>", "□", "■");
        String publication = html.substring(html.indexOf("data-report-fact=\"공고일\""), html.indexOf("data-report-fact=\"관련 일정\""));
        assertThat(publication).contains("color:#243247;\"").doesNotContain("color:#215fa8", "color:#243247;font-weight:700");
        assertThat(html.substring(html.indexOf("data-report-fact=\"제출·의견 기한\"")))
                .contains("color:#243247;font-weight:700");
    }

    @Test void rendersLegacyScreenshotAndClearChoicesAsAReviewablePreview() throws Exception {
        String body = "신규 2건 │ 수정 0건 │ 변경 없음 0건\n"
                + "▸ 기관별 신청 자격이 확인된 공고\n기관: 예시 기관\n문서 유형: 사업 공고 │ 신규 │ 기회점수: 72점\n"
                + "요약: 기관별 신청 자격과 실제 제출 마감을 구분해 안내합니다. 공동 참여 조건을 확인한 뒤 신청 서류를 준비해 주세요.\n"
                + "• 제출 주체·대상: " + CLEAR + " ※ 참여제한 기관은 제외합니다.\n"
                + "• 제출·의견 기한: 공고일: 2026년 10월 6일 / 접수 마감: 2026년 10월 30일 18:00. 우편은 도착분만 유효합니다.\n"
                + "• 제출처·방법: [온라인 신청](https://example.org/apply)\n"
                + "원문: [게시글 보기](https://example.org/notice)\n"
                + "▸ 선택 정보가 불명확한 기존 보고서\n기관: 예시 기관\n문서 유형: 사업 공고 │ 변경 없음 │ 기회점수: 58점\n"
                + "요약: 원문에서 선택 상태를 확인할 수 없는 기관 유형은 신청 가능 대상으로 단정하지 않습니다.\n"
                + "• 제출 주체·대상: " + UNCLEAR + "\n• 제출·의견 기한: 공고일: 2026년 10월 6일\n"
                + "원문: [게시글 보기](https://example.org/legacy)";
        String html = ReportEmailTemplate.render("GovInsight · 신청 안내 보고서", body);
        assertThat(html).contains("기관별 신청 자격이 확인된 공고", "선택 정보가 불명확한 기존 보고서",
                "참여제한 기관은 제외합니다.", "접수 마감: 2026년 10월 30일 18:00", "주관기관 외 필수 참여기관:");
        assertThat(html).doesNotContain("국공립연", "외국법인", "□", "■");
        Path output = Path.of("build/report-preview/role-readability.html");
        Files.createDirectories(output.getParent());
        Files.writeString(output, html);
    }
}
