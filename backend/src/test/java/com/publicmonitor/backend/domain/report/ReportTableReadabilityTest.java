package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReportTableReadabilityTest {
    private static final String BROKEN = "□ 신청 자격 및 포상규모(안) : 산업통상부장관 표창 8점 "
            + "포상대상 부문 훈격 규모 수공기간 산업혁신인재성지원에 기여한 전문기관 종사자 "
            + "유공자 장관표창 2 3년 이상 산업혁신인재성지원에 기여한 연구개발기관(주관·공동) 종사자 6 계 8";

    @Test void oldSavedTableCannotLeakIntoAnyFactOrSummary() {
        for (String label : new String[]{"제출 주체·대상", "제출·의견 기한", "제출처·방법", "문의 담당"}) {
            String html = ReportEmailTemplate.render("보고서", "▸ 공고\n요약: " + BROKEN + "\n• " + label + ": " + BROKEN);
            assertThat(html).contains("원문 표에서", "사업 내용과 세부 조건");
            assertThat(html).doesNotContain("훈격", "6 계 8", "□");
        }
    }

    @Test void clearListsSentencesNumbersAndExceptionsArePreserved() {
        assertThat(ReportFactPresentation.lines("제출 주체·대상",
                "□ 신청 자격: 중소기업 ■ 업력 3년 이상. 단, 참여제한 기관은 제외합니다."))
                .containsExactly("중소기업", "업력 3년 이상. 단, 참여제한 기관은 제외합니다.");
        assertThat(ReportFactPresentation.lines("제출처·방법",
                "접수는 온라인으로 진행합니다. 우편은 접수하지 않습니다."))
                .containsExactly("접수는 온라인으로 진행합니다.", "우편은 접수하지 않습니다.");
        String value = "2026년 10월 14일 18:00까지 / 3.5억원 / https://example.org/a.b?x=3.5";
        assertThat(ReportFactPresentation.lines("제출·의견 기한", value)).containsExactly(value);
    }

    @Test void completeMailHasReadableSummaryFactsAndDownloads() throws Exception {
        String body = "▸ 산업혁신인재성 지원 유공자 포상 공고\n기관: 산업통상부\n"
                + "요약: 산업혁신인재성 지원에 기여한 유공자를 포상합니다. 신청 방법과 제출 서류를 확인해 주세요.\n"
                + "• 제출 주체·대상: " + BROKEN + "\n"
                + "• 제출·의견 기한: 2026년 10월 14일 18:00까지. 우편은 도착분만 유효합니다.\n"
                + "• 제출처·방법: □ 온라인 접수 ■ 담당 부서 이메일로 제출\n"
                + "• 문의 담당: 사업지원팀 02-1234-5678 / help@example.org\n"
                + "제출 준비 서류 ↓\n• [제출양식.hwp](https://example.org/form)\n"
                + "  ↳ 관련 서류: 신청서 · 개인정보동의서\n• 재직증명서\n"
                + "원문: [게시글 보기](https://example.org/notice)";
        String html = ReportEmailTemplate.render("공공기관 모니터링 보고서", body);
        assertThat(html.split("data-report-summary-item", -1)).hasSize(3);
        assertThat(html).contains("신청 자격과 제외 조건", "우편은 도착분만 유효합니다.", "help@example.org");
        assertThat(html).doesNotContain("6 계 8", "훈격", "□", "■", "↳");
        Path output = Path.of("build/report-preview/table-readability.html");
        Files.createDirectories(output.getParent());
        Files.writeString(output, html);
    }
}
