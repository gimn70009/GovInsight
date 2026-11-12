package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class EmailReviewBriefTest {
    @Test
    void keepsCompanyCaveatsAndConditionalRequirementsButExcludesOldUrgency() {
        var detail = BusinessSummaryEmailFixtures.parse("""
                {"analysis":{"opportunity":{"dimensions":[
                  {"type":"COMPANY_FIT","reason":"연관성은 있지만 필수 실적은 미확인입니다."},
                  {"type":"URGENCY","reason":"회사 행동 없음. 남은 20일입니다."}]},
                  "proposal":{"preparation":{"eligibilityChecklist":[
                    {"title":"기업 규모","appliesTo":"주관기업","detail":"중소기업만 신청 가능하며 연구기관은 제외합니다.",
                     "requirementLevel":"CONDITIONAL","nextAction":"중소기업 확인서를 확인합니다."}]}}}}
                """);
        var input = EmailReviewBrief.source(10, detail);
        assertThat(input.sources()).extracting(EmailReviewBrief.Source::text)
                .anyMatch(text -> text.contains("미확인"))
                .anyMatch(text -> text.contains("주관기업") && text.contains("연구기관은 제외"))
                .noneMatch(text -> text.contains("남은 20일") || text.contains("회사 행동 없음"));
    }

    @Test
    void demoAndMissingAnalysisDoNotTriggerModelCalls() {
        assertThat(EmailReviewBrief.source(1, BusinessSummaryEmailFixtures.parse("{\"analysis\":null}"))).isNull();
        assertThat(EmailReviewBrief.source(1, BusinessSummaryEmailFixtures.parse("""
                {"analysis":{"proposal":{"usesDemoProfile":true},"opportunity":{"dimensions":[
                 {"type":"COMPANY_FIT","reason":"가상 회사 적합도"}]}}}
                """))).isNull();
    }
}
