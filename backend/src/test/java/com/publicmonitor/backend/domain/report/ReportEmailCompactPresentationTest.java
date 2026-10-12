package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReportEmailCompactPresentationTest {
    private static final String ROUTES = "계획서 및 첨부서류 온라인 일괄 접수\n"
            + "오프라인 서류제출 불필요\n전산등록과 동시에 첨부 서류를 온라인으로 접수\n"
            + "전산 등록처: 범부처통합연구지원시스템(IRIS, www.iris.go.kr)\n"
            + "※ 사전에 반드시 접수전 필수 이행사항을 조치하여야 함\n"
            + "필수이행사항: 이용자 회원가입 및 기관대표자 등록, 기관 담당자 권한 부여\n"
            + "※ 회원가입과 사전 등록 등에 시간이 소요될 수 있으므로 사전 등록 권장\n".repeat(18)
            + "※ 허위·위변조한 자료는 선정 취소 및 불이익 조치\n"
            + "재무제표 제출처: 원클릭서비스(m.one-click.co.kr/#/ketep)\n"
            + "※ 최초 이용시 약관 동의와 인증서 준비 필요";

    @Test void longRecoveredRoutesBecomeTwoExplicitLinkedChannels() {
        assertThat(ROUTES.length()).isGreaterThan(900);
        assertThat(ReportEmailCompactPresentation.lines("제출처·방법", ROUTES)).containsExactly(
                "[IRIS 온라인 접수](https://www.iris.go.kr)",
                "[재무제표: 원클릭 제출](https://m.one-click.co.kr/#/ketep)");
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n• 제출처·방법: "
                + ROUTES.replace("\n", "\n  ↳ ") + "\n원문: [게시글 보기](https://example.org/notice)");
        assertThat(html).contains("href=\"https://m.one-click.co.kr/#/ketep\"", "게시글 보기</a>");
        assertThat(html).doesNotContain("필수이행사항", "사전 등록", "※", "위변조", "aria-hidden=\"true\"");
    }

    @Test void twoObligationsOnOneLineKeepTheirOwnRolesAndLinks() {
        String raw = "영문서 접수처: 유레카 사무국 https://eureka.smartsimple.ie/ "
                + "국문서 접수처: 한국산업기술진흥원 https://k-pass.kr/ (온라인 접수)";
        assertThat(ReportEmailCompactPresentation.lines("제출처·방법", raw)).containsExactly(
                "[영문 접수: 접수 페이지](https://eureka.smartsimple.ie/)",
                "[국문 접수: K-PASS 온라인 접수](https://k-pass.kr/)");
    }

    @Test void explicitCountryObligationsWithoutUrlsRemainSeparate() {
        assertThat(ReportEmailCompactPresentation.lines("제출처·방법",
                "영문 계획서: 유레카 사무국 제출\n국문 계획서: KIAT 시스템 제출"))
                .containsExactly("영문 계획서: 유레카 사무국 제출", "국문 계획서: KIAT 시스템 제출");
    }

    @Test void postalThenEmailRetainsOrderAndNeverInventsVisitPermission() {
        assertThat(ReportEmailCompactPresentation.lines("제출처·방법",
                "우편 접수 후 이메일 제출\n방문접수는 받지 않습니다\n이메일: apply@example.org"))
                .containsExactly("우편 접수 후 이메일 제출", "이메일: apply@example.org");
        assertThat(ReportEmailCompactPresentation.lines("제출처·방법",
                "우편 접수 후 이메일 제출\n방문접수는 마감일 18시까지 접수함"))
                .containsExactly("우편·방문 접수 후 이메일 제출");
    }

    @Test void portalDoesNotEraseRequiredPaperOriginal() {
        assertThat(ReportEmailCompactPresentation.lines("제출처·방법",
                "IRIS 온라인 접수: https://iris.go.kr\n온라인 접수 후 원본 우편 제출"))
                .containsExactly("[IRIS 온라인 접수](https://iris.go.kr)", "온라인 접수 후 원본 우편 제출");
    }

    @Test void simultaneousSubmissionConditionRemainsBesideTwoPortals() {
        assertThat(ReportEmailCompactPresentation.lines("제출처·방법",
                "국문 접수: https://example.org/korean\n영문 접수: https://example.org/english\n"
                + "반드시 두 사이트에 동시에 접수"))
                .hasSize(3).contains("반드시 두 사이트에 동시에 접수");
    }

    @Test void inlinePaperOriginalConditionKeepsItsWholeShortClause() {
        assertThat(ReportEmailCompactPresentation.lines("제출처·방법",
                "IRIS 온라인 접수: https://iris.go.kr (온라인 접수 후 원본 우편 제출, 2부 필수)"))
                .containsExactly("[IRIS 온라인 접수](https://iris.go.kr)",
                        "온라인 접수 후 원본 우편 제출, 2부 필수");
    }

    @Test void inlineSimultaneousConditionRemainsBesideBothPortalLinks() {
        assertThat(ReportEmailCompactPresentation.lines("제출처·방법",
                "국문 접수: https://example.org/ko 영문 접수: https://example.org/en 반드시 두 사이트에 동시에 접수"))
                .containsExactly("[국문 접수: 접수 페이지](https://example.org/ko)",
                        "[영문 접수: 접수 페이지](https://example.org/en)",
                        "반드시 두 사이트에 동시에 접수");
    }

    @Test void unboundedInlineObligationFallsBackInsteadOfLosingItsConditions() {
        String detail = "온라인 접수 후 원본 우편 제출, " + "추가 적용 조건을 확인해야 합니다. ".repeat(10);
        assertThat(ReportEmailCompactPresentation.lines("제출처·방법",
                "IRIS 온라인 접수: https://iris.go.kr (" + detail + ")"))
                .containsExactly("원문에서 확인해 주세요.");
    }

    @Test void uncertainLongTextUsesSourceInsteadOfCuttingASentence() {
        assertThat(ReportEmailCompactPresentation.lines("제출처·방법", "불명확한 원문 설명입니다. ".repeat(40)))
                .containsExactly("원문에서 확인해 주세요.");
    }

    @Test void contactsLoseSourceSymbolsAndKeepPurposeInstitutionAndChannels() {
        String raw = "o 연구개발계획서 전산 등록 관련 문의 : IRIS 고객센터(☎ 1877-0000, 042-000-0000)\n"
                + "※ o 재무제표 온라인 전용시스템 제출 관련 문의 : 예시평가정보(☎ 02-0000-0000)";
        var rows = ReportEmailCompactPresentation.lines("문의 담당", raw);
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst()).contains("접수 시스템", "IRIS 고객센터", "1877-0000", "042-000-0000");
        assertThat(rows.getLast()).contains("재무제표", "예시평가정보", "02-0000-0000");
        assertThat(String.join("\n", rows)).doesNotContain("☎", "※", "o ");
    }

    @Test void splitContactsMergeAndOnlyTwoCompleteRowsAreShown() {
        assertThat(ReportEmailCompactPresentation.lines("문의 담당",
                "한국지원기관 사업지원실\n연락처 02-0000-0000/0001\n이메일 help@example.org (우편 접수 후 이메일 제출)"))
                .containsExactly("한국지원기관 사업지원실: 02-0000-0000/0001, help@example.org");
        assertThat(ReportEmailCompactPresentation.lines("문의 담당",
                "영문 접수: 해외기관 overseas@example.org\n국문 접수: 국내기관 02-0000-0000\n사업 문의: 지원실 02-0000-0001"))
                .hasSize(2);
    }

    @Test void sourceHtmlAndUnsafeUrlsNeverBecomeExecutableMarkup() {
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n"
                + "• 제출처·방법: 온라인 접수: https://user@example.org/apply\n"
                + "• 문의 담당: <img src=x onerror=alert(1)> 지원팀 02-0000-0000\n"
                + "원문: [위험](javascript:alert(1))");
        assertThat(html).doesNotContain("<img ", "href=\"https://user@", "href=\"javascript:");
        assertThat(html).contains("&lt;img", "02-0000-0000");
    }

    @Test void emailDomainsDoNotBecomeApplicationLinks() {
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n• 제출처·방법: 이메일 제출: apply@example.org");
        assertThat(html).doesNotContain("href=\"https://example.org");
        assertThat(html).contains("apply@example.org");
    }

    @Test void fullSafeUrlsRemainInHrefWhileLabelsStayShort() {
        String address = "https://example.org/apply?token=" + "a".repeat(100) + "&lang=ko";
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n• 제출처·방법: 온라인 접수: " + address);
        assertThat(html).contains("href=\"" + address.replace("&", "&amp;") + "\">온라인 접수 페이지</a>");
        assertThat(html).doesNotContain("\">" + address);
    }

    @Test void checklistUsesCompactRowsInsteadOfSeparateCards() {
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n제출 준비 서류 ↓\n"
                + "• [신청서](https://example.org/form)\n• 사업자등록증 사본\n원문: [게시글 보기](https://example.org/notice)");
        assertThat(html).contains("data-submission-list-item", "신청서</a>", "사업자등록증 사본");
        assertThat(html).doesNotContain("padding:12px 14px", "background:#f7f8fa");
    }

    @Test void writesCompactEmailPreview() throws Exception {
        String body = "▸ 두 접수처가 있는 공고\n요약: 사업 신청을 받습니다.\n• 제출처·방법: "
                + ROUTES.replace("\n", "\n  ↳ ") + "\n• 문의 담당: o 접수 문의: 지원팀(☎ 02-0000-0000)\n"
                + "원문: [게시글 보기](https://example.org/notice)";
        Path path = Path.of("build/report-preview/email-compact.html");
        Files.createDirectories(path.getParent());
        Files.writeString(path, ReportEmailTemplate.render("핵심 안내", body));
    }
}
