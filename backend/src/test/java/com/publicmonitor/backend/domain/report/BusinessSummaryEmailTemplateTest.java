package com.publicmonitor.backend.domain.report;

import com.publicmonitor.backend.domain.email.EmailBrand;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessSummaryEmailTemplateTest {
    @Test
    void 여섯_행_사업정보와_세_행_검토사항을_별도_표로_표시한다() throws Exception {
        var notice = BusinessSummaryEmailView.from(BusinessSummaryEmailFixtures.jumpUp(),
                java.time.LocalDateTime.of(2026, 11, 6, 9, 0), 7);
        var general = new BusinessSummaryEmailView("해당 없음", List.of(), "재난관리책임기관 지정 및 고시", "국토교통부",
                "확인 필요", "소관 기관을 지정하여 고시하는 내용입니다.", "확인 필요", 20, "변경", "https://example.org/general");
        var multi = new BusinessSummaryEmailView("연구개발(R&D)", List.of(
                new BusinessSummaryEmailView.Organization("긴 기관명과 부서명을 포함한 연구개발사업 관리 전담기관", "전담기관")),
                "기관명이 길고 여러 접수 의무가 있는 공동연구 공고", "과학기술정보통신부",
                "과제당 연간 최대 9억 300만 원, 총 2년 이내 / 자부담 25% 이상",
                "지원 대상과 사업 목적을 안내합니다.",
                "온라인 10월 30일 18시\n원본 우편 11월 2일 도착분", 88, "변경 없음", "https://example.org/research");
        var result = BusinessSummaryEmailTemplate.render("[공공기관 모니터링] 10월 16일 보고서", List.of(notice, general, multi), false);
        assertThat(result.html()).contains("data-business-summary-table", "font-size:12px", "기회점수 72점",
                "사업 유형", "사업 담당기관", "사업명", "지원규모", "사업 설명", "제출기한", "이노비즈협회", "공고 원문 보기", "data-report-original-link");
        assertThat(result.html()).contains("data-report-header", "MONITORING REPORT", "data-report-institution",
                "중소벤처기업부", "국토교통부", "과학기술정보통신부", "신규 1건  |  수정 1건  |  변경 없음 1건");
        assertThat(result.plainText()).contains("공공기관: 중소벤처기업부");
        assertThat(result.html().split("data-business-summary-row", -1)).hasSize(19);
        assertThat(result.html().split("data-business-review-table", -1)).hasSize(4);
        assertThat(result.html().split("data-business-review-row", -1)).hasSize(10);
        assertThat(result.html()).contains("종합 검토", "우선 확인사항", "준비 일정", "오늘 준비 착수 권장",
                "마감까지 D-7일", "color:#a6352d", "기업 규모와 매출 요건", "등록된 제조 기술");
        assertThat(result.html()).doesNotContain("발송일 기준", "최소 준비 7일", "display:flex", "display:grid");
        assertThat(result.plainText()).contains("종합 검토:", "우선 확인사항:", "준비 일정: 오늘 준비 착수 권장 · 마감까지 D-7일");
        assertThat(result.html()).doesNotContain("핵심 요약", "참여조건", "제출 안내", "문의 안내", "사업 방식",
                "<script", "<details", "<button", "이전의 긴 보고서용 요약", "근거 원문", "data-organization-evidence", "notice.pdf");
        assertThat(result.plainText()).contains(notice.purpose(), notice.supportScale(), notice.deadline(), "기회점수 72점", "문의처 · 이노비즈협회");
        assertThat(result.plainText()).doesNotContain("핵심 요약", "이전의 긴 보고서용 요약", "notice.pdf");
        var output = Path.of("build/report-preview");
        Files.createDirectories(output);
        Files.writeString(output.resolve("email-business-summary.html"), result.html());
        Files.writeString(output.resolve("email-business-summary.txt"), result.plainText());
    }

    @Test
    void 원문_텍스트를_이스케이프하고_링크는_HTTP와_HTTPS만_허용한다() {
        var notice = new BusinessSummaryEmailView("<script>alert(1)</script>",
                List.of(new BusinessSummaryEmailView.Organization("<img src=x>", "문의처")),
                "<svg onload=alert(1)>", "<기관>", "<table>1억</table>", "설명 & 내용", "확인 필요",
                null, "변경", "https://example.org/file?q=\"x\"&a=1");
        var result = BusinessSummaryEmailTemplate.render("<제목>", List.of(notice), false);
        assertThat(result.html()).contains("&lt;script&gt;", "&lt;img", "&lt;svg", "&lt;기관&gt;", "설명 &amp; 내용", "기회점수 미산정");
        assertThat(result.html().replace(EmailBrand.html(), "")).doesNotContain("<script", "<img", "<svg", "href=\"javascript:", "data-report-original-link");
        assertThat(result.html().split("<img ", -1)).hasSize(2);
        assertThat(result.html()).contains("src=\"cid:govinsight-brand\"");
        assertThat(result.plainText()).contains("설명 & 내용");
    }

    @Test
    void 공고가_없는_실행과_조회_자료_누락을_구분한다() {
        assertThat(BusinessSummaryEmailTemplate.render("보고서", List.of(), false).plainText())
                .contains("확인된 공고가 없습니다");
        assertThat(BusinessSummaryEmailTemplate.render("보고서", List.of(), true).plainText())
                .contains("공고 정보를 확인하지 못했습니다").doesNotContain("확인된 공고가 없습니다");
    }

    @Test
    void 제보된_두_기한과_모의_AI_요약을_실제_메일_템플릿에_표시한다() throws Exception {
        String[] deadlines = {
            "전산등록 마감 2026-11-27 12:00, 서류접수(우편 도착 기준) 마감 2026-11-27 16:00.",
            "회사 행동 기한은 2026-11-20 16:00까지 온라인 제출이며 남은 17일입니다."
        };
        var notices = new java.util.ArrayList<BusinessSummaryEmailView>();
        for (int i = 0; i < deadlines.length; i++) {
            var detail = BusinessSummaryEmailFixtures.parse("""
                    {"title":"메일 검토 안내 검증 사례 %s","organizationName":"공고기관", "changeType":"NEW_DOCUMENT",
                     "originalUrl":"https://example.org/notice","businessType":{"status":"CLASSIFIED","code":"RND"},
                     "analysis":{"applicationDeadline":"%s", "summary":"회사 기술과 관련된 연구개발 및 실증 참여 기회입니다.",
                      "opportunity":{"totalScore":72,"dimensions":[{"type":"URGENCY","reason":"회사 행동 없음. 결과 발표입니다."}]}}}
                    """.formatted(i + 1, deadlines[i]));
            // Fixed model responses exercise display only; no external provider call.
            var review = new EmailReviewBrief.Result(i + 1,
                    "제조 AI 기술을 실증하고 사업화 실적을 확보할 기회입니다. 참여 자격과 필수 실적은 추가 확인이 필요합니다.",
                    List.of("주관기관과 필수 공동기관의 참여 요건을 확인하세요.", "기관장 직인이 있는 제출 공문을 준비하세요."));
            notices.add(BusinessSummaryEmailView.from(detail, java.time.LocalDateTime.of(2026, 11, 3, 9, 0), 7, review));
        }
        var result = BusinessSummaryEmailTemplate.render("메일 검토 안내 · 모의 요약 검증", notices, false);
        assertThat(result.plainText()).contains("마감까지 D-24일", "마감까지 D-17일", "기관장 직인이 있는 제출 공문");
        assertThat(result.plainText()).doesNotContain("준비 일정 해당 없음", "마감일 확인 필요", "↵", "①");
        var output = Path.of("build/report-preview");
        Files.createDirectories(output);
        Files.writeString(output.resolve("email-review-mocked.html"), result.html());
    }
}
