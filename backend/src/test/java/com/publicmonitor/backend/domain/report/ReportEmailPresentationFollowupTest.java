package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReportEmailPresentationFollowupTest {
    private static final String BUSINESS = """
            ▸ 2026년도 일본 다자간 국제공동기술개발사업 공고
            기관: 예시 기술진흥원
            문서 유형: 일반 공지 │ 변경 없음 │ 기회점수: 69점
            공고 성격: 사업 기회
            사업 유형: R&D·기술개발
            사업 방식: 연구개발비 지원
            요약: 일본 기업과 공동으로 기술을 개발할 국내 컨소시엄을 모집합니다. 선정된 과제의 연구개발비를 지원합니다.
            • 금액·규모: 과제당 연 5억 원 이내로 최대 3년간 지원합니다. 지원예산과 기간은 명시된 조건 이내에서 평가 결과에 따라 변동될 수 있습니다.
            • 참여 역할: 국내 컨소시엄 내 영리기업 참여 필수 * 지원예산 및 지원기간은 명시된 조건 이내에서 지원하되, 평가 결과에 따라 변동될 수 있습니다.
            • 제출 주체·대상: 국내 주관기관: 중소·중견기업. * 해외 영리기업 참여 필수. ※ 채무불이행 기업 제외.
            • 제출·의견 기한: 영문 연구개발계획서 접수 마감: 2027년 1월 20일 / 국문 연구개발계획서 접수 마감: 2027년 1월 21일 16:00(KST)
            • 제출처·방법: 영문 접수: https://example.org/international / 국문 접수: https://k-pass.kr/
            • 문의 담당: ▸IRIS 시스템 관련: IRIS 콜센터 또는 IRIS 홈페이지 사용문의 게시판: 1877-2041
              ↳ ▶연구사업단: 02-0000-7253, 02-0000-7255, project@example.org, support@example.org
              ↳ □ 계약 담당: 02-0000-9000 (내선 123), contract@example.org
            원문: [게시글 보기](https://example.org/notice)
            """;

    private static final String GENERAL = """
            ▸ 건축물관리지원센터 지정 고시
            기관: 예시 부처
            문서 유형: 일반 공지 │ 변경 없음 │ 기회점수: 25점
            공고 성격: 일반 공지
            요약: 건축물 관리 업무를 지원할 기관의 지정 사실을 고시합니다.
            • 문의 담당: ▷ 제도 담당: 02-0000-1234, policy@example.org
            원문: [게시글 보기](https://example.org/designation)
            """;

    @Test void everyCardHasScoreCategoryAndStateIncludingUnconfirmedClassification() {
        String unknown = GENERAL.replace("공고 성격: 일반 공지", "공고 성격: 확인 필요");
        for (String report : new String[] {BUSINESS, GENERAL, unknown}) {
            String html = ReportEmailTemplate.render("보고서", report);
            assertThat(html).containsSubsequence("data-report-score", "data-report-category", "data-report-status");
            assertThat(html.split("data-report-category", -1)).hasSize(2);
            assertThat(html).doesNotContain("유형 ·", "방식 ·");
        }
        assertThat(ReportEmailTemplate.render("보고서", BUSINESS)).contains("사업 공고</span>");
        assertThat(ReportEmailTemplate.render("보고서", GENERAL)).contains("일반 공지</span>");
        assertThat(ReportEmailTemplate.render("보고서", unknown)).contains("분류 확인 필요</span>");
    }

    @Test void summaryComesFirstForBusinessGeneralAndLegacyCards() {
        for (String report : new String[] {BUSINESS, GENERAL, BUSINESS.replace("공고 성격: 사업 기회\n", "")}) {
            String html = ReportEmailTemplate.render("보고서", report);
            assertThat(html.substring(html.indexOf("data-report-section="))).startsWith("data-report-section=\"summary\"");
        }
        String html = ReportEmailTemplate.render("보고서", BUSINESS);
        assertThat(html).containsSubsequence("data-report-section=\"summary\"", "data-report-section=\"amount\"", "data-report-section=\"participation\"");
    }

    @Test void contactsHaveSeparateLabelsAndChannelsWithoutDroppingThirdTeam() {
        String html = ReportEmailTemplate.render("보고서", BUSINESS);
        String contact = html.substring(html.indexOf("data-report-section=\"contact\""));
        assertThat(contact.split("data-report-contact-group", -1)).hasSize(4);
        assertThat(contact).contains("IRIS", "연구사업단", "계약 담당", "1877-2041", "02-0000-7253", "02-0000-7255",
                "project@example.org", "support@example.org", "contract@example.org", "내선 123");
        assertThat(contact).contains("data-report-contact-channel=\"phone\"", "data-report-contact-channel=\"email\"");
        assertThat(contact).doesNotContain("▸", "▶", "□", "문의 담당</div>");
    }

    @Test void storedParticipationNotesLoseDecorationButKeepMandatoryAndExcludedConditions() {
        String html = ReportEmailTemplate.render("보고서", BUSINESS);
        assertThat(html).contains("영리기업 참여 필수", "채무불이행 기업 제외", "평가 결과에 따라 변동");
        assertThat(html).doesNotContain("* 지원예산", "* 해외", "※ 채무");
        assertThat(ReportFactPresentation.lines("참여 역할", "C++ 개발기업 3 * 5 프로젝트 / R&D 참여"))
                .containsExactly("C++ 개발기업 3 * 5 프로젝트 / R&D 참여");
    }

    @Test void repeatedBudgetNoteAppearsOnlyUnderAmountAndUniqueRoleConditionRemains() {
        String note = "지원예산 및 지원기간은 평가 결과에 따라 변동될 수 있습니다.";
        String body = "▸ 사업 공고\n요약: 컨소시엄을 모집합니다.\n"
                + "• 금액·규모: 과제당 연 5억 원. " + note + "\n"
                + "• 참여 역할: 국내 영리기업 참여 필수 * " + note
                + " * 지원기간 중 컨소시엄 유지 필수\n원문: https://example.org/notice";
        String html = ReportEmailTemplate.render("보고서", body);
        String role = html.substring(html.indexOf("data-report-section=\"participation\""));
        assertThat(role).contains("국내 영리기업 참여 필수", "지원기간 중 컨소시엄 유지 필수")
                .doesNotContain(note);
        assertThat(html).contains(note);
        String unique = ReportEmailTemplate.render("보고서", body.replace("과제당 연 5억 원. " + note, "과제당 연 5억 원."));
        assertThat(unique.substring(unique.indexOf("data-report-section=\"participation\""))).contains(note);
    }

    @Test void contactBoardLinksRemainSafeAndClickable() {
        String html = ReportEmailTemplate.render("보고서", GENERAL.replace(
                "▷ 제도 담당: 02-0000-1234, policy@example.org",
                "[문의 게시판](https://example.org/help?a=1&b=2)"));
        assertThat(html).contains("href=\"https://example.org/help?a=1&amp;b=2\"", ">문의 게시판</a>");
        String hostile = ReportEmailTemplate.render("보고서", GENERAL.replace(
                "▷ 제도 담당", "<script>alert(1)</script> 담당"));
        assertThat(hostile).contains("&lt;script&gt;").doesNotContain("<script>");
    }

    @Test void producesConsistentSummaryAndContactPreview() throws Exception {
        String unknown = GENERAL.replace("건축물관리지원센터 지정 고시", "신규 공고 분류 확인 중")
                .replace("공고 성격: 일반 공지", "공고 성격: 확인 필요");
        String html = ReportEmailTemplate.render("공공기관 동향 보고서", BUSINESS + GENERAL + unknown);
        Path target = Path.of("build/report-preview/email-summary-contacts.html");
        Files.createDirectories(target.getParent());
        Files.writeString(target, html);
    }
}
