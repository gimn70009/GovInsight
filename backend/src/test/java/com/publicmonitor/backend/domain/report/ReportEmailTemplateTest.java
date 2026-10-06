package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReportEmailTemplateTest {
    @ParameterizedTest
    @CsvSource({
            "0, #f1f5f9, #94a3b8, #475569",
            "39, #f1f5f9, #94a3b8, #475569",
            "40, #e7effb, #357bd8, #215fa8",
            "49, #e7effb, #357bd8, #215fa8",
            "50, #e7effb, #357bd8, #215fa8",
            "69, #e7effb, #357bd8, #215fa8",
            "74, #e7effb, #357bd8, #215fa8",
            "75, #fef2f2, #dc2626, #b91c1c",
            "100, #fef2f2, #dc2626, #b91c1c"
    })
    void colorsHeaderAndScoreBadgeAtScoreBoundaries(int score, String background, String accent, String badge) {
        String html = ReportEmailTemplate.render("보고서", scoredCard(score + "점"));
        assertThat(html).contains("background:" + background + ";border-top:4px solid " + accent,
                "background:" + badge + ";color:#ffffff;font-weight:700\">기회점수: " + score + "점</span>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"미산정", "-1점", "101점", "999999999999점", "75.5점", "75", "75점 추가", "<b>90점</b>"})
    void unknownOrInvalidScoresUseGrayAndPreserveEscapedText(String score) {
        String html = ReportEmailTemplate.render("보고서", scoredCard(score));
        assertThat(html).contains("background:#f1f5f9;border-top:4px solid #94a3b8",
                "background:#475569;color:#ffffff;font-weight:700\">기회점수: "
                        + ReportBodyFormatter.html(score) + "</span>");
    }

    @Test void ignoresScoresOutsideTheHeaderWhenScoreIsMissing() {
        String html = ReportEmailTemplate.render("보고서", "▸ 기회점수: 99점 공고\n기관: 기관 80점\n"
                + "문서 유형: 사업 공고 │ 신규\n요약: 기회점수: 90점\n"
                + "문서 유형: 사업 공고 │ 신규 │ 기회점수: 100점");
        assertThat(html).contains("background:#f1f5f9;border-top:4px solid #94a3b8")
                .doesNotContain("border-top:4px solid #dc2626");
    }

    @Test void eachCardUsesItsOwnScoreAndWritesColorPreview() throws Exception {
        String html = ReportEmailTemplate.render("[공공기관 모니터링] 10월 1일 보고서",
                "신규 2건 │ 수정 1건 │ 변경 없음 1건\n\n" + scoredCard("82점")
                + scoredCard("69점") + scoredCard("35점") + scoredCard("미산정"));
        String[] cards = html.split("padding-top:20px");
        assertThat(cards).hasSize(5);
        assertThat(cards[1]).contains("border-top:4px solid #dc2626", "기회점수: 82점");
        assertThat(cards[2]).contains("border-top:4px solid #357bd8", "기회점수: 69점");
        assertThat(cards[3]).contains("border-top:4px solid #94a3b8", "기회점수: 35점");
        assertThat(cards[4]).contains("border-top:4px solid #94a3b8", "기회점수: 미산정");
        var output = java.nio.file.Path.of("build/report-preview/opportunity-score-colors.html");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, html);
    }

    private static String scoredCard(String score) {
        return "▸ 규제자유특구, 글로벌혁신특구 및 광역연계형특구 후보과제 모집 공고\n"
                + "기관: 한국산업기술진흥원\n문서 유형: 일반 공지 │ 신규 │ 기회점수: " + score
                + "\n요약: 지역 혁신을 위한 후보과제를 모집하는 공고입니다.\n"
                + "• 제출·의견 기한: 2026년 11월 6일 16:00\n"
                + "원문: [게시글 보기](https://example.org/notice)\n\n";
    }

    @Test void stylesTitlesFactsAndAttachmentsWithoutChangingTheirValues() {
        String body = "신규 1건 │ 수정 0건 │ 변경 없음 0건\n\n────────────────\n\n"
                + "▸ 참여기업 모집\n기관: 예시 기관\n문서 유형: 사업 공고 │ 신규 │ 기회점수: 72점\n\n"
                + "요약: 지원사업 요약입니다.\n\n• 제출·의견 기한: 2026-11-06 16:00\n"
                + "• 제출처·방법: 원문 확인 필요\n\n첨부파일 ↓\n• [신청서.hwp](https://example.org/file?a=1&b=2)\n"
                + "원문: [게시글 보기](https://example.org/notice)";
        String html = ReportEmailTemplate.render("보고서", body);
        assertThat(html).contains("<h2 style=", "font-size:22px", "참여기업 모집</h2>", "기회점수: 72점", "제출 안내",
                "2026-11-06 16:00", "원문 확인 필요", "파일명을 눌러 다운로드", "href=\"https://example.org/file?a=1&amp;b=2\">신청서.hwp</a>");
        assertThat(html).doesNotContain("────────────────", "▸ 참여기업 모집", "문서 유형: ");
    }

    @Test void preservesTheCorrectedPrimarySummaryInEmail() {
        String summary = "과학기술정보통신부의 공고는 2026년도 한-스웨덴 공동연구사업의 신규과제 "
                + "선정결과를 알리는 결과 공고입니다.";
        String body = "▸ 2026년도 한-스웨덴 공동연구사업 신규과제 선정결과 공고\n기관: 과학기술정보통신부\n"
                + "요약: " + summary + "\n원문: [게시글 보기](https://example.org/notice)";
        String html = ReportEmailTemplate.render("보고서", body);
        assertThat(html).contains(summary + "</p>").doesNotContain("원-스웨덴", "요약: ");
    }

    @Test void submissionUrlsKeepTheirFullAddressAndWrapInEmail() throws Exception {
        String url = "https://www.kiat.or.kr/front/board/boardContentsView.do?MenuId="
                + "a".repeat(100) + "&contents_id=notice#apply";
        String body = "▸ 접수 안내\n• 제출처·방법: KIAT 접수처: 서울특별시 강남구 테헤란로 305 및 www.kiat.or.kr\n"
                + "  ↳ 온라인 접수: (" + url + ")\n원문: [게시글 보기](https://example.org/notice)";
        String html = ReportEmailTemplate.render("보고서 · 접수 URL", body);
        assertThat(html).contains("href=\"https://www.kiat.or.kr\">www.kiat.or.kr</a>",
                "href=\"" + url.replace("&", "&amp;") + "\">" + url.replace("&", "&amp;") + "</a>)",
                "word-break:break-all", "word-wrap:break-word", "white-space:normal");
        var output = java.nio.file.Path.of("build/report-preview/submission-urls.html");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, html);
    }

    @Test void rendersMultipleCardsAndPreservesTrailingOmissionNotice() {
        String body = "▸ 첫 공고\n기관: 기관1\n\n요약: 첫 요약\n\n────────────────\n\n"
                + "▸ 둘째 공고\n기관: 기관2\n\n첨부파일 ↓\n• 등록된 첨부파일 없음\n"
                + "원문: 원문 확인 필요\n\n그 외 3건은 상세 화면에서 확인하세요.";
        String html = ReportEmailTemplate.render("보고서", body);
        assertThat(html).contains("공고 01", "공고 02", "첫 공고</h2>", "둘째 공고</h2>", "그 외 3건");
    }

    @Test void untrustedSourceHtmlAndUnsafeLinksCannotInjectMarkup() {
        String html = ReportEmailTemplate.render("<script>",
                "▸ <img src=x onerror=alert(1)>\n요약: <script>alert(1)</script>\n"
                + "첨부파일 ↓\n• [위험](javascript:alert(1))");
        assertThat(html).contains("&lt;script&gt;", "&lt;img", "javascript:alert(1)");
        assertThat(html).doesNotContain("<script>", "<img ", "href=\"javascript:");
    }

    @Test void legacyTextStillKeepsNewlinesAndLinks() {
        assertThat(ReportEmailTemplate.render("기존 보고서", "기존 본문\n[원문](https://example.org/notice)"))
                .contains("기존 본문<br>", "href=\"https://example.org/notice\">원문</a>");
    }
    @Test void rendersRealisticLongTitlesAndWritesLocalPreview() throws Exception {
        String body = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/reports/email-brief.txt"));
        String html = ReportEmailTemplate.render("[공공기관 모니터링] 9월 22일 보고서", body);
        assertThat(html).contains("광역연계형특구 후보과제 모집 공고</h2>", "기회점수: 65점");
        var output = java.nio.file.Path.of("build/report-preview/email-brief.html");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, html);
    }
    @Test void rendersSubmissionChecklistAndWritesPreview() throws Exception {
        String body = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/reports/submission-checklist.txt"));
        String html = ReportEmailTemplate.render("보고서 · 제출 준비 안내", body);
        assertThat(html).contains("제출 준비 서류</h3>", "통합 서류 파일</a>",
                "납세증명서(해당 시)", "신청서</a>", "사업자등록증 사본");
        assertThat(html).contains("data-report-note", "미확인 항목: 제출처·방법, 문의 담당");
        assertThat(html).doesNotContain("원문 확인 필요", "확인된 제출 서류가 없습니다");
        assertThat(html).doesNotContain("data-submission-card", "준비 담당", "ZIP 내부 파일", "◆ ", "첨부파일 <span");
        var output = java.nio.file.Path.of("build/report-preview/submission-checklist.html");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, html);
    }

    @Test void submissionCardsEscapeSourceAndKeepOriginalLinkOutsideLastCard() {
        String body = "▸ 공고\n요약: 안내\n제출 준비 서류 ↓\n◆ 필수 │ <신청서>\n"
                + "제출 주체: 주관기관\n준비 담당(권장): 사업 담당\n양식: [양식](https://example.org/form)\n"
                + "원문: [게시글 보기](https://example.org/notice)";
        String html = ReportEmailTemplate.render("보고서", body);
        assertThat(html).contains("필수 │ &lt;신청서&gt;", "href=\"https://example.org/form\"");
        assertThat(html).doesNotContain("<신청서>");
        assertThat(html).contains("</div></div><div style=\"margin-top:22px");
    }
    @Test void separatesKoreanGuidanceAndUsesContactHeadingForResults() throws Exception {
        String body = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/reports/korean-guidance.txt"));
        String html = ReportEmailTemplate.render("보고서 · 한국어 제출 안내", body);
        assertThat(html).contains("제출 안내</h3>", "문의 안내</h3>", "국내 주관기관:", "스페인:");
        assertThat(html.split("data-report-fact-item", -1)).hasSize(10);
        assertThat(html).doesNotContain("↳", "미확인 항목:", "data-report-note");
        var output = java.nio.file.Path.of("build/report-preview/korean-guidance.html");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, html);
    }

    @Test void continuationValuesEscapeUntrustedMarkup() {
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n• 제출처·방법: 한국: 온라인\n  ↳ 해외: <script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;", "data-report-fact-item");
        assertThat(html).doesNotContain("<script>", "↳");
    }
}
