package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReportEmailContentClarityTest {
    private static final String QUANTUM = """
            ▸ 2026년도 퀀텀(양자)플랫폼 양자공동연구실 신규과제 공고
            기관: 예시 연구기관
            문서 유형: 일반 공지 │ 변경 없음 │ 기회점수: 69점
            공고 성격: 사업 기회
            사업 유형: R&D·기술개발
            사업 방식: 연구개발비 지원
            요약: 과학기술정보통신부가 KIST 사업단을 통해 퀀텀(양자)플랫폼의 양자활용연구거점 양자공동연구실 신규과제를 공모합니다. 모집 RFP는 양자 하드웨어와 양자컴퓨팅 응용 소프트웨어·시스템 2개로, 과제당 정부출연금은 총 약 9.03억 원이며 과제기간은 2026.11.01~2029.12.31입니다.
            • 금액·규모: 과제당 정부출연금은 총 약 9.03억 원이며 과제기간은 2026.11.01~2029.12.31입니다.
            • 제출 주체·대상: 산학연 공동연구 컨소시엄
            • 제출·의견 기한: 신청 마감: 2026년 11월 5일 18:00
            • 제출처·방법: IRIS 온라인 접수: https://www.iris.go.kr/
            • 문의 담당: 사업명 : 퀀텀(양자)플랫폼 양자활용연구거점 양자공동연구실(JQL)
              ↳ IRIS 문의처: IRIS 콜센터 1877-2041 또는 IRIS 홈페이지 사용문의 게시판 활용
              ↳ IRIS 콜센터 1877-2041 또는 IRIS 홈페이지 사용문의 게시판 활용
            원문: [게시글 보기](https://example.org/quantum)
            """;
    private static final String NOTICE = """
            ▸ 조기재취업 수당 지급제외 임금액 고시 일부개정안 행정예고
            기관: 예시 부처
            문서 유형: 일반 공지 │ 변경 없음 │ 기회점수: 22점
            공고 성격: 일반 공지
            요약: 조기재취업 수당 지급제외 대상의 임금액을 월 5,740,000원에서 3,000,000원으로 낮추는 일부개정안을 행정예고했습니다. 의견 제출 기한은 2026년 10월 18일까지이며 우편 또는 전자우편으로 접수합니다.
            • 제출·의견 기한: 의견 제출 기한: 2026년 10월 18일
            • 제출처·방법: 우편 또는 전자우편 접수
            • 문의 담당: 주택공사 선교통환경처 (055-922-3880)로 문의하시기 바랍니다.
            원문: [게시글 보기](https://example.org/policy)
            """;

    @Test void allClassificationStatesHaveTwoRowsWithAnExplicitMeaning() {
        String missing = QUANTUM.replace("사업 유형: R&D·기술개발", "사업 유형: 확인 필요")
                .replace("사업 방식: 연구개발비 지원", "사업 방식: 확인 필요");
        String absent = missing.replace("사업 유형: 확인 필요", "").replace("사업 방식: 확인 필요", "");
        for (String source : new String[]{QUANTUM, missing, absent, NOTICE}) {
            String html = ReportEmailTemplate.render("보고서", source);
            assertThat(html.split("data-report-meta=\"type\"", -1)).hasSize(2);
            assertThat(html.split("data-report-meta=\"method\"", -1)).hasSize(2);
        }
        assertThat(ReportEmailTemplate.render("보고서", missing)).contains("원문 확인 필요");
        assertThat(ReportEmailTemplate.render("보고서", absent)).contains("원문 확인 필요");
        assertThat(ReportEmailTemplate.render("보고서", NOTICE)).contains("해당 없음 (일반 공지)")
                .doesNotContain("원문 확인 필요");
    }

    @Test void emptyMetadataDoesNotHideFollowingKnownMethodOrLeakIntoBody() {
        String source = QUANTUM.replace("기관: 예시 연구기관", "기관: ")
                .replace("사업 유형: R&D·기술개발", "사업 유형: ");
        String html = ReportEmailTemplate.render("보고서", source);
        assertThat(html).contains("원문 확인 필요</td>", "연구개발비 지원</td>")
                .doesNotContain("사업 방식:", "사업 유형:");
        assertThat(section(html, "summary")).contains("양자공동연구실");
    }

    @Test void contactHeadingAndDuplicateIrisAreRemovedWithoutRemovingTheBoard() {
        String contact = section(ReportEmailTemplate.render("보고서", QUANTUM), "contact");
        assertThat(contact.split("data-report-contact-group", -1)).hasSize(2);
        assertThat(contact.split("1877-2041", -1)).hasSize(2);
        assertThat(contact).contains("IRIS 콜센터", "IRIS 홈페이지 사용문의 게시판 활용")
                .doesNotContain("사업명", "양자공동연구실", "IRIS 문의처");
    }

    @Test void summaryRetainsResearchContentWhileAmountAndPeriodRemainInTheirSection() {
        String html = ReportEmailTemplate.render("보고서", QUANTUM);
        assertThat(section(html, "summary")).contains("양자공동연구실 신규과제를 공모", "양자 하드웨어", "응용 소프트웨어·시스템")
                .doesNotContain("9.03", "2026.11.01", "2029.12.31", "2개로,");
        assertThat(section(html, "amount")).contains("9.03억 원", "2026.11.01", "2029.12.31");
    }

    @Test void policyContentSurvivesAndContactSentenceHasNoDanglingParenthesisOrSuffix() {
        String html = ReportEmailTemplate.render("보고서", NOTICE);
        assertThat(section(html, "summary")).contains("5,740,000원", "3,000,000원", "일부개정안")
                .doesNotContain("의견 제출 기한", "2026년 10월 18일", "우편 또는 전자우편");
        assertThat(section(html, "submission")).contains("2026년 10월 18일", "우편 또는 전자우편");
        assertThat(section(html, "contact")).contains("주택공사 선교통환경처", "055-922-3880")
                .doesNotContain("(055", "로 문의하시기 바랍니다", "data-report-contact-channel=\"detail\"");
    }

    @Test void hiddenComplexRouteCannotBeUsedToRemoveTheOnlyReadableRouteInSummary() {
        String report = "▸ 사업 공고\n공고 성격: 사업 기회\n요약: 기술개발을 지원합니다. 접수는 IRIS 온라인으로 진행합니다.\n"
                + "• 제출처·방법: IRIS 온라인 접수: https://www.iris.go.kr/\n"
                + "  ↳ K-PASS 온라인 접수: https://k-pass.kr/\n"
                + "  ↳ 영문 접수: https://example.org/en\n"
                + "  ↳ 국문 접수: https://example.org/ko\n";
        String html = ReportEmailTemplate.render("보고서", report);
        assertThat(section(html, "summary")).contains("IRIS 온라인으로 진행합니다");
        assertThat(section(html, "submission")).contains("원문에서 확인해 주세요");
    }

    @Test void generatesReviewPreview() throws Exception {
        String unknown = QUANTUM.replace("퀀텀(양자)플랫폼 양자공동연구실 신규과제 공고", "사업 유형·방식 미확인 사례")
                .replace("사업 유형: R&D·기술개발", "사업 유형: 확인 필요")
                .replace("사업 방식: 연구개발비 지원", "사업 방식: 확인 필요");
        Path target = Path.of("build/report-preview/email-content-clarity.html");
        Files.createDirectories(target.getParent());
        Files.writeString(target, ReportEmailTemplate.render("공공기관 동향 보고서", QUANTUM + NOTICE + unknown));
    }

    private static String section(String html, String name) {
        int start = html.indexOf("data-report-section=\"" + name + "\"");
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = html.indexOf("data-report-section=", start + 1);
        return html.substring(start, end < 0 ? html.length() : end);
    }
}
