package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReportReadabilityTest {
    private static final String APPLICANT = "가. 신청자격 ㅇ 산업별 인적자원개발협의체 활동에 직접적으로 종사하고 "
            + "구체적·객관적으로 공적이 입증된 자로서 정부포상업무지침에 의해 추천된 자 "
            + "ㅇ 국가관, 사명감이 투철하고 공.사 생활이 타의 귀감이 되며, 봉사와 선행에 앞장서는 등 능력과 품성을 두루 겸비한 자";

    @Test void oldSavedReportIsFormattedIntoDistinctItems() throws Exception {
        var items = ReportFactPresentation.lines("제출 주체·대상", APPLICANT);
        assertThat(items).hasSize(2);
        assertThat(items.getFirst()).startsWith("산업별").contains("정부포상업무지침에 의해 추천된 자");
        assertThat(items.getLast()).startsWith("국가관").contains("공·사");
        String body = "▸ 산업별 인적자원개발 유공 포상 공고\n기관: 관계 기관\n"
                + "문서 유형: 일반 공지 │ 변경 없음 │ 기회점수: 42점\n"
                + "요약: 산업별 인적자원개발 활동의 공적이 있는 대상자를 추천하는 공고입니다.\n"
                + "• 제출 주체·대상: " + APPLICANT + "\n"
                + "• 제출·의견 기한: 표제: 2026년 10월 14일 18:00까지\n"
                + "• 제출처·방법: 온라인 접수\n원문: [게시글 보기](https://example.org/notice)";
        String html = ReportEmailTemplate.render("GovInsight 모니터링 보고서", body);
        assertThat(html).doesNotContain("가. 신청자격", "ㅇ ", "표제:");
        assertThat(html).contains("공·사", "추천된 자", "능력과 품성을 두루 겸비한 자");
        var output = Path.of("build/report-preview/readability.html");
        Files.createDirectories(output.getParent()); Files.writeString(output, html);
    }

    @Test void exceptionAndUntrustedMarkupArePreservedAndEscaped() {
        assertThat(ReportFactPresentation.lines("제출 주체·대상",
                "신청 대상: 중소기업 ㅇ 단, 참여제한 기관은 제외합니다."))
                .containsExactly("중소기업", "단, 참여제한 기관은 제외합니다.");
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n• 제출 주체·대상: 신청 대상: <script>위험</script>");
        assertThat(html).contains("&lt;script&gt;").doesNotContain("<script>");
        assertThat(ReportFactPresentation.lines("제출처·방법", "한국: https://example.org/a/b?x=1.2"))
                .containsExactly("한국: https://example.org/a/b?x=1.2");
        assertThat(ReportFactPresentation.lines("제출 주체·대상", "신청 자격이 없는 기관은 제외"))
                .containsExactly("신청 자격이 없는 기관은 제외");
    }
}
