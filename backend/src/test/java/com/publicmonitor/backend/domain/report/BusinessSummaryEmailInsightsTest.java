package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class BusinessSummaryEmailInsightsTest {
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 11, 3, 9, 0);

    @Test
    void 저장된_회사_연결점과_가치를_재사용하고_핵심_보완행동_두개를_우선한다() {
        var insights = read("""
                {"analysis":{"opportunity":{"dimensions":[
                  {"type":"BUSINESS_VALUE","reason":"공공판로 확대 기회가 있습니다."},
                  {"type":"COMPANY_FIT","reason":"보유 제조 AI 기술과 연결됩니다."},
                  {"type":"URGENCY","reason":"분석 당시 남은 20일입니다."}]},
                  "proposal":{"preparation":{"strategy":{"criticalGaps":[
                    {"gap":"신청 자격 미확인","nextAction":"인증 유효기간을 확인합니다."},
                    {"gap":"파트너 미확보","nextAction":"공동 참여기관을 확인합니다."},
                    {"gap":"후순위","nextAction":"추가 정보를 확인합니다."}]},
                    "eligibilityChecklist":[{"title":"후순위 체크리스트","nextAction":"서류를 확인합니다."}]}}}}
                """);
        assertThat(insights.reviewReason()).isEqualTo("보유 제조 AI 기술과 연결됩니다. 공공판로 확대 기회가 있습니다.");
        assertThat(insights.priorityChecks()).containsExactly("인증 유효기간을 확인합니다.",
                "공동 참여기관을 확인합니다.");
        assertThat(insights.schedule().deadlineBadge()).isEqualTo("마감까지 D-7일");
    }

    @Test
    void 체크리스트의_대상과_예외를_보존한다() {
        var insights = read("""
                {"analysis":{"proposal":{"preparation":{"eligibilityChecklist":[
                 {"title":"기업 규모","appliesTo":"주관기업","detail":"중소기업만 신청 가능하며 연구기관은 제외합니다.",
                  "requirementLevel":"CONDITIONAL","nextAction":"중소기업 확인서를 확인합니다."}]}}}}
                """);
        assertThat(insights.priorityChecks()).containsExactly(
                "주관기업에 적용되는 참여 요건과 예외 조건을 원문에서 확인해 주세요.");
    }

    @Test
    void 전략과_실행가능성으로_대체하되_비어있는_분석을_만들어내지_않는다() {
        var insights = read("""
                {"analysis":{"opportunity":{"dimensions":[{"type":"FEASIBILITY","reason":"필수 인증 확보 여부를 확인해야 합니다."}]},
                  "proposal":{"preparation":{"strategy":{"decisionReason":"공공 실적 확보를 위한 조건부 검토입니다."}}}}}
                """);
        assertThat(insights.reviewReason()).isEqualTo("공공 실적 확보를 위한 조건부 검토입니다.");
        assertThat(insights.priorityChecks()).containsExactly("필수 인증 확보 여부를 확인해야 합니다.");
        assertThat(read("{\"analysis\":null}").reviewReason()).isEqualTo("저장된 분석 정보가 없습니다. 공고 원문을 확인해 주세요.");
    }

    @Test
    void 데모_회사_분석을_실제_회사_검토_이유로_보여주지_않는다() {
        var insights = read("""
                {"analysis":{"opportunity":{"dimensions":[{"type":"COMPANY_FIT","reason":"가상 회사의 기술과 연결됩니다."}]},
                 "proposal":{"usesDemoProfile":true}}}
                """);
        assertThat(insights.reviewReason()).contains("회사 정보를 등록").doesNotContain("가상 회사");
        assertThat(insights.schedule().deadlineBadge()).isEqualTo("마감까지 D-7일");
    }

    @Test
    void 과거_행동_없음_분석보다_현재_제출기한을_우선한다() {
        var insights = read("""
                {"analysis":{"opportunity":{"dimensions":[{"type":"URGENCY","reason":"회사 행동 없음. 결과 발표입니다."}]}}}
                """);
        assertThat(insights.schedule().deadlineBadge()).isEqualTo("마감까지 D-7일");
        var detail = BusinessSummaryEmailFixtures.parse("""
                {"analysis":{"opportunity":{"dimensions":[{"type":"URGENCY","reason":"회사 행동 없음. 결과 발표입니다."}]}}}
                """);
        assertThat(BusinessSummaryEmailInsights.from(detail, null, SENT_AT, 7).schedule())
                .isEqualTo(new EmailPreparationSchedule("준비 일정 해당 없음", null));
        assertThat(BusinessSummaryEmailInsights.from(detail, "온라인 2026-11-10 또는 별도 통지", SENT_AT, 7).schedule())
                .isEqualTo(EmailPreparationSchedule.unknown());
    }

    @Test
    void 분석에_섞인_HTML과_문장_조각을_메일에_노출하지_않는다() {
        var detail = BusinessSummaryEmailFixtures.parse("""
                {"analysis":{"opportunity":{"dimensions":[
                  {"type":"COMPANY_FIT","reason":"<script>alert(1)</script> & 검토"},
                  {"type":"FEASIBILITY","reason":"<img src=x onerror=alert(1)>"}]}}}
                """);
        var view = BusinessSummaryEmailView.from(detail, SENT_AT, 7);
        var content = BusinessSummaryEmailTemplate.render("검증", java.util.List.of(view), false);
        assertThat(content.html()).doesNotContain("<script>", "<img src=x", "alert(1)");
        assertThat(content.plainText()).doesNotContain("alert(1)").contains("원문에서 확인해 주세요.");
    }

    @Test
    void 짧은_AI_문장을_표시하되_일정은_발송일로_계산한다() {
        var detail = BusinessSummaryEmailFixtures.jumpUp();
        var review = new EmailReviewBrief.Result(1, "제조 AI 실증과 공공판로 확보 기회입니다. 필수 실적은 확인이 필요합니다.",
                java.util.List.of("주관기관과 필수 공동기관의 참여 요건을 확인하세요.", "기관장 직인이 있는 제출 공문을 준비하세요."));
        var value = BusinessSummaryEmailInsights.from(detail,
                "회사 행동 기한은 2026-11-20 16:00까지 온라인 제출이며 남은 17일입니다.", SENT_AT.plusDays(5), 7, review);
        assertThat(value.reviewReason()).isEqualTo(review.reviewReason());
        assertThat(value.priorityChecks()).isEqualTo(review.priorityChecks());
        assertThat(value.schedule().deadlineBadge()).isEqualTo("마감까지 D-12일");
    }

    @Test
    void 요약_실패시_긴_문장을_잘라_조건을_없애거나_표_조각을_노출하지_않는다() {
        var detail = BusinessSummaryEmailFixtures.parse("""
                {"analysis":{"opportunity":{"dimensions":[{"type":"COMPANY_FIT","reason":"%s"}]},
                  "proposal":{"preparation":{"strategy":{"criticalGaps":[
                   {"gap":"제출 공문","nextAction":"제출 공문을 준비합니다. 필수 ① 제출 공문 기관 공문 기관장 직인본 ↵ (HWPX 또는 PDF)"}]}}}}}
                """.formatted("긴 회사 분석 문장입니다. ".repeat(20)));
        var value = BusinessSummaryEmailInsights.from(detail, "2026-11-20", SENT_AT, 7);
        assertThat(value.reviewReason()).contains("연관성을 검토해 주세요.").doesNotContain("긴 회사 분석", "제공하지 못");
        assertThat(value.priorityChecks()).containsExactly("참여 요건을 원문에서 확인해 주세요.");
    }

    @Test
    void 신청서_원문_조각은_노출하지_않고_완결된_행동은_그대로_사용한다() {
        var value = read("""
                {"analysis":{"proposal":{"preparation":{"strategy":{"criticalGaps":[
                 {"gap":"연구개발기관 자격","nextAction":"회사의 법적 기관 유형과 등록 현황이 연구개발기관 요건에 부합하는지 확인하세요."},
                 {"gap":"신청서","nextAction":"연구개발신청서를 준비합니다. - 연구개발신청서 원본 1부(연구개발계획서 양식 첫페이지)"}]}}}}}
                """);
        assertThat(value.priorityChecks()).containsExactly(
                "회사의 법적 기관 유형과 등록 현황이 연구개발기관 요건에 부합하는지 확인하세요.");
    }

    private BusinessSummaryEmailInsights read(String json) {
        return BusinessSummaryEmailInsights.from(BusinessSummaryEmailFixtures.parse(json), "2026-11-10", SENT_AT, 7);
    }
}
