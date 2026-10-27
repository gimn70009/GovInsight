package com.publicmonitor.backend.domain.report;

import com.publicmonitor.backend.domain.email.EmailBrand;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessSummaryEmailTemplateTest {
    @Test
    void 여섯_행과_작은_배지만_표시하고_기존_상세_섹션을_제거한다() throws Exception {
        var notice = BusinessSummaryEmailView.from(BusinessSummaryEmailFixtures.jumpUp());
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
}
