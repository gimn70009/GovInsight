package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReportSubmissionDownloadsTest {
    private static final String URL = "https://example.org/file?id=1&seq=2";

    @Test void sameDownloadIsShownOnceWithAllRequirementNames() throws Exception {
        String body = "▸ 현장실습 지원사업\n제출 준비 서류 ↓\n"
                + "• [사업계획서](" + URL + ")\n• 결산재무제표\n"
                + "• [표준 현장실습 학기제 운영계획서](" + URL + ")\n"
                + "• 사업자 등록증\n• 기관소개 자료\n원문: [게시글 보기](https://example.org/notice)";
        String html = ReportEmailTemplate.render("제출 준비 서류", body);
        assertThat(html.split("href=\"https://example.org/file", -1)).hasSize(2);
        assertThat(html).contains("통합 서류 파일</a>", "결산재무제표", "사업자 등록증", "기관소개 자료")
                .doesNotContain("관련 서류:", "표준 현장실습 학기제 운영계획서");
        Path output = Path.of("build/report-preview/unique-downloads.html");
        Files.createDirectories(output.getParent());
        Files.writeString(output, html);
    }

    @Test void groupingIsScopedToSubmissionSectionOfEachNotice() {
        String card = "▸ 공고\n제출 준비 서류 ↓\n• [신청서](" + URL + ")\n• [동의서](" + URL + ")\n"
                + "원문: [게시글 보기](" + URL + ")\n첨부파일 ↓\n• [첨부](" + URL + ")\n";
        String html = ReportEmailTemplate.render("보고서", card + card);
        assertThat(html.split("href=\"https://example.org/file", -1)).hasSize(7);
    }

    @Test void differentQueriesRemainDistinctWithoutRelatedDocumentDescriptions() {
        String body = "▸ 공고\n제출 준비 서류 ↓\n• [신청서](" + URL + "#page=1)\n"
                + "• [동의서](" + URL + "#page=2) (선택 제출)\n"
                + "• [별도 신청서](" + URL + "0)";
        String html = ReportEmailTemplate.render("보고서", body);
        assertThat(html.split("href=\"https://example.org/file", -1)).hasSize(3);
        assertThat(html).contains("별도 신청서</a>").doesNotContain("관련 서류:", "동의서 (선택 제출)");
    }

    @Test void generatedGroupedFileShowsOnlyFileName() {
        String body = "▸ 공고\n제출 준비 서류 ↓\n• [붙임2 사업계획서.hwp](" + URL + ")\n"
                + "  ↳ 관련 서류: 사업계획서 · <script>운영계획서</script>";
        String html = ReportEmailTemplate.render("보고서", body);
        assertThat(html).contains("붙임2 사업계획서.hwp</a>");
        assertThat(html).doesNotContain("<script>", "↳", "관련 서류:", "운영계획서");
    }
}
