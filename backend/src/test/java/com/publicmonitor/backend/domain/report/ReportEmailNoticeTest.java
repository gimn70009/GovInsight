package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportEmailNoticeTest {
    private static final String CONFLICT = "안내: 요약과 제출 안내의 일정이 다릅니다. 원문에서 확인한 아래 제출 기한을 우선 확인해 주세요.";

    @ParameterizedTest
    @ValueSource(strings = {
            CONFLICT,
            "안내: 요약에 적힌 제출 기한은 원문 근거를 확인하지 못했습니다. 신청 전에 원문을 확인해 주세요."
    })
    void hiddenDiagnosticDoesNotLeaveAnUnreliableDateInTheSummary(String diagnostic) {
        String body = "▸ 사업 공고\n요약: 연구개발을 지원합니다. 신청은 2026.10.20까지 접수합니다. "
                + "신청 기업에 1.5 억원을 최대 3년 지원합니다.\n" + diagnostic
                + "\n• 제출·의견 기한: 신청 마감: 2026년 10월 26일 18:00까지"
                + "\n• 제출처·방법: 온라인 접수: https://example.org/apply"
                + "\n원문: [게시글 보기](https://example.org/notice)";
        String html = ReportEmailTemplate.render("보고서", body);
        assertThat(html).contains("연구개발을 지원합니다.", "신청 기업에 1.5 억원을 최대 3년 지원합니다.",
                "2026년 10월 26일 18:00까지", "href=\"https://example.org/apply\"", "공고 원문 보기</a>");
        assertThat(html).doesNotContain("2026.10.20", diagnostic, "data-report-note", "일정이 다릅니다");
        assertThat(body).contains("2026.10.20", diagnostic);
    }

    @Test void allDateSummaryHasNoEmptyHeadingAndFilteringIsScopedToItsCard() {
        String html = ReportEmailTemplate.render("보고서", "▸ 첫 공고\n요약: 신청 마감은 2026년 10월 20일입니다.\n"
                + CONFLICT + "\n• 제출·의견 기한: 신청 마감: 2026년 10월 26일\n"
                + "원문: [게시글 보기](https://example.org/first)\n"
                + "▸ 둘째 공고\n요약: 신청 마감은 2026년 11월 3일입니다.\n"
                + "원문: [게시글 보기](https://example.org/second)");
        assertThat(html).doesNotContain("2026년 10월 20일");
        assertThat(html).contains("2026년 10월 26일", "신청 마감은 2026년 11월 3일입니다.");
        assertThat(html.split("핵심 요약", -1)).hasSize(2);
    }

    @Test void repeatedNotesAndInstructionsAreHiddenButFormsAndConditionsRemain() throws Exception {
        String body = "신규 1건\n안내: 일부 접수 정보는 원문에서 확인해 주세요.\n"
                + "▸ 사업 공고\n요약: 연구개발을 지원합니다.\n"
                + "• 제출·의견 기한: 신청 마감: 2026년 10월 26일\n"
                + "안내: 신청 자격·제출 기한 항목은 원문에서 확인해 주세요.\n"
                + "제출 준비 서류 ↓\n• [신청서](https://example.org/form)\n• 납세증명서 (해당 시)\n"
                + "미확인 항목: 문의 담당\n원문: [게시글 보기](https://example.org/notice)";
        String html = ReportEmailTemplate.render("보고서", body);
        assertThat(html).doesNotContain("안내:", "미확인 항목:", "data-report-note", "상세 조건·절차",
                "파일을 내려받아 작성", "회사 자료를 준비", "제출 전 원문의 최신 안내");
        assertThat(html).contains("공고에서 제공한 양식", "회사에서 준비할 서류", "납세증명서 (해당 시)",
                "href=\"https://example.org/form\"", "href=\"https://example.org/notice\"");
        Path preview = Path.of("build/report-preview/email-without-notices.html");
        Files.createDirectories(preview.getParent());
        Files.writeString(preview, html);
    }

    @Test void aNoteInsideLegacyDocumentCardsDoesNotExposeDiagnosticsOrRemoveTheCondition() {
        String html = ReportEmailTemplate.render("보고서", "▸ 사업 공고\n제출 준비 서류 ↓\n◆ 신청서\n"
                + "제출 조건: 원본 2부 제출\n안내: 일부 항목은 원문에서 확인해 주세요.\n"
                + "원문: [게시글 보기](https://example.org/notice)");
        assertThat(html).contains("원본 2부 제출", "공고 원문 보기</a>").doesNotContain("안내:", "일부 항목");
    }
}
