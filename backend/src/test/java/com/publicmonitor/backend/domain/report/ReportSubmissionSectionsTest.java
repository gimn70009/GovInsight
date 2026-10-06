package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReportSubmissionSectionsTest {
    @Test void mixedChecklistSeparatesProvidedFormsFromCompanyDocuments() throws Exception {
        String body = "▸ 첨단분야 인턴십 지원사업\n기관: 공공기관\n"
                + "요약: 참여기업은 신청 자격을 확인하고 필요한 서류를 준비합니다.\n"
                + "제출 준비 서류 ↓\n"
                + "• 결산재무제표\n"
                + "• [붙임2 첨단분야 인턴십 지원 사업 신청 안내 및 사업계획서 양식.hwp](https://example.org/form)\n"
                + "  ↳ 관련 서류: 사업계획서 · 표준 현장실습 학기제 운영계획서\n"
                + "• 사업자 등록증\n• 기관소개 자료 (선택 제출)\n"
                + "안내: 일부 접수 정보는 원문에서 확인해 주세요.\n"
                + "원문: [게시글 보기](https://example.org/notice)";
        String html = ReportEmailTemplate.render("공공기관 모니터링 보고서", body);
        int forms = html.indexOf("data-submission-section=\"provided\"");
        int company = html.indexOf("data-submission-section=\"company\"");
        assertThat(forms).isGreaterThanOrEqualTo(0).isLessThan(company);
        assertThat(html.substring(forms, company)).contains("공고에서 제공한 양식", "파일을 내려받아 작성",
                "사업계획서 양식.hwp</a>").doesNotContain("결산재무제표", "관련 서류:", "표준 현장실습 학기제 운영계획서");
        assertThat(html.substring(company)).contains("회사에서 준비할 서류", "결산재무제표", "사업자 등록증",
                "기관소개 자료 (선택 제출)", "링크가 없는 양식은 공고 원문에서 확인");
        assertThat(html.split("href=\"https://example.org/form\"", -1)).hasSize(2);
        Path output = Path.of("build/report-preview/submission-sections.html");
        Files.createDirectories(output.getParent());
        Files.writeString(output, html);
    }

    @Test void emptyGroupsAreNotRendered() {
        String formOnly = ReportEmailTemplate.render("보고서",
                "▸ 공고\n제출 준비 서류 ↓\n• [신청서](https://example.org/form)");
        assertThat(formOnly).contains("data-submission-section=\"provided\"")
                .doesNotContain("data-submission-section=\"company\"");
        String companyOnly = ReportEmailTemplate.render("보고서",
                "▸ 공고\n제출 준비 서류 ↓\n• 사업자등록증");
        assertThat(companyOnly).contains("data-submission-section=\"company\"")
                .doesNotContain("data-submission-section=\"provided\"");
    }

    @Test void missingOrUnsafeFormLinkIsNotClaimedAsProvided() {
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n제출 준비 서류 ↓\n"
                + "• 신청서\n• [동의서](https://user:pass@example.org/form)");
        assertThat(html).doesNotContain("data-submission-section=\"provided\"", "href=\"https://user:");
        assertThat(html).contains("링크가 없는 양식은 공고 원문에서 확인", "신청서");
    }

    @Test void sectionsKeepDuplicateGroupingAndAreScopedToEachNotice() {
        String card = "▸ 공고\n제출 준비 서류 ↓\n"
                + "• [신청서](https://example.org/forms.zip)\n"
                + "• [동의서](https://example.org/forms.zip)\n"
                + "• 사업자등록증\n추가 제출서류 2종: 원문에서 확인\n"
                + "원문: [게시글 보기](https://example.org/notice)\n";
        String html = ReportEmailTemplate.render("보고서", card + card);
        assertThat(html.split("data-submission-section=\"provided\"", -1)).hasSize(3);
        assertThat(html.split("data-submission-section=\"company\"", -1)).hasSize(3);
        assertThat(html.split("href=\"https://example.org/forms.zip\"", -1)).hasSize(3);
        assertThat(html.split("추가 제출서류 2종", -1)).hasSize(3);
    }
}
