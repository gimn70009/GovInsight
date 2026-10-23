package com.publicmonitor.backend.domain.document.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;

class ParticipationLocalConditionsTest {
    ParticipationGuide project(String body) {
        var c = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(c.guide(), false, c.scopes());
        return ParticipationRewriteProjection.guide(c.guide(), input, Response.unavailable(input.request()), "UNAVAILABLE");
    }

    @Test void missingModelOutputIsSupplementedForAllExplicitPrograms() {
        String body = "1. 성장형\n□ (사업목적) 기업의 혁신과 경영체질을 지원하여 육성\n□ (지원대상) 업력 6년 이상의 매출액 20억원 달성* 중소기업\n"
                + "2. 도약형\n□ (지원대상) 업력 6년 이상의 매출액 40억원 달성* 중소기업\n"
                + "3. 확장형\n□ (지원대상) 업력 6년 이상의 매출액 80억원 달성* 중소기업";
        var guide = project(body);
        assertThat(guide.rules()).hasSize(3).allSatisfy(rule -> {
            assertThat(rule.text()).endsWith("지원 대상입니다.").doesNotContain("□", "*", "사업목적");
            assertThat(rule.scope()).doesNotContain("사업목적");
        });
        assertThat(guide.rules()).extracting(ParticipationRule::scope).containsExactly("성장형", "도약형", "확장형");
        assertThat(guide.rules()).extracting(ParticipationRule::text).anyMatch(t -> t.contains("40억원"));
        assertThat(guide.rewriteStatus()).isEqualTo("UNAVAILABLE");
    }

    @Test void unlabelledOrUnresolvedFragmentsNeverBecomeConditions() {
        assertThat(project("사업목적은 기업의 성장 지원\n기업 지원 안내").rules()).isEmpty();
        assertThat(project("□ 제출서류\n중소기업 확인서 제출").rules()).isEmpty();
        assertThat(project("□ 지원대상\n아래 조건에 해당하는 기업\n세부 내용은 별표 참조").rules()).isEmpty();
    }

    @Test void wrappedHeadcountQualifierIsNotDropped() {
        var guide = project("가. 신청대상\n◦ 소공인\n(상시근로자수 10인 미만의 제조업을 영위하는 소상공인)\n[표]\n신청 조건\t추가 안내\n[/표]");
        assertThat(guide.rules()).singleElement().satisfies(rule ->
                assertThat(rule.text()).contains("상시근로자수 10인 미만", "제조업"));
    }

    @Test void tableAlternativesRetainBothBranchesAndTheCompanyRequirement() {
        var guide = project("□ 과제별 신청자격\n선택과제\n(기 업) 중소기업자로 아래 요건 중 어느 하나에 해당하는 기업\n"
                + "[표]\n기업 구분\n①\t설립 6년 이하 창업기업\n②\t공공기관 계약실적이 3억원 이하인 기업\n[/표]");
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.scope()).isEqualTo("선택과제");
            assertThat(rule.text()).contains("중소기업자 중", "설립 6년 이하", "또는", "3억원 이하");
            assertThat(rule.text()).doesNotContain("[표]", "①");
        });
    }

    @Test void explicitExceptionRemainsInOneSentenceAndUnsupportedExceptionIsNotErased() {
        var guide = project("□ 지원대상 : 전년도 직접수출액 4,000만불 미만*의 중소기업\n* 단, 지정기업은 4,000만불 이상도 지원 가능");
        assertThat(guide.rules()).singleElement().satisfies(rule ->
                assertThat(rule.text()).doesNotContain("*").contains("4,000만불 미만", "지정기업은 4,000만불 이상도 지원할 수 있습니다."));
        assertThat(project("□ 지원대상 : 업력 6년 이상인 중소기업\n※ 단, 세부 예외는 별표 참조").rules()).isEmpty();
    }

    @Test void exactOriginalEvidenceIsKeptForEveryLocalSentence() {
        String body = "□ 신청 자격\n◦ 기술 실증 지원기능을 보유한 전문 기관";
        var guide = project(body);
        assertThat(guide.rules()).singleElement().satisfies(rule -> assertThat(rule.text()).contains("전문 기관"));
        guide.categories().stream().flatMap(c -> c.rows().stream()).flatMap(r -> r.entries().stream())
                .filter(e -> e.displayStatus().equals("VERIFIED")).forEach(entry ->
                        entry.supportingQuotes().forEach(quote -> assertThat(body).contains(quote)));
    }

    @Test void repeatedTableIntroductionsUseTheirOwnFollowingAlternatives() {
        String intro = "(기 업) 중소기업자로 아래 요건 중 어느 하나에 해당하는 기업\r\n";
        String body = "□ 과제별 신청자격\r\n첫과제\r\n" + intro
                + "[표]\r\n①\t설립 6년 이하 창업기업\r\n②\t매출액 3억원 이상 기업\r\n[/표]\r\n둘째과제\r\n" + intro
                + "[표]\r\n①\t설립 9년 이하 창업기업\r\n②\t매출액 8억원 이상 기업\r\n[/표]";
        assertThat(project(body).rules()).anySatisfy(rule -> {
            assertThat(rule.scope()).isEqualTo("둘째과제");
            assertThat(rule.text()).contains("9년", "8억원").doesNotContain("6년", "3억원");
        });
    }

    @Test void mathematicalMultiplicationIsNeverRemovedAsDecoration() {
        assertThat(ParticipationLocalConditions.clean("매출액* 2 ≥ 30인 기업")).contains("매출액* 2 ≥ 30");
    }

    @Test void researchObligationKeepsRoleDeadlinesRecognitionAndExcludedDepartment() {
        String quote = "주관연구개발기관이 기업인 경우는 접수마감일 현재 법인사업자이어야 하며, 선정평가 개최일 이전에 기업부설연구소(한국산업기술진흥협회 인정서 기준)를 보유하고 있어야 함(연구개발전담부서는 불인정)";
        String body = "4-1. 지원분야\nR&D자율성트랙\n◦ 연구 지원\n4-2. 신청자격\n□ 주관연구개발기관 및 공동연구개발기관\n◦" + quote;
        var guide = project(body);
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.scope()).isNull();
            assertThat(rule.text()).isEqualTo("주관연구개발기관이 기업인 경우, 접수마감일 현재 법인사업자여야 하며, 선정평가 개최일 이전에 기업부설연구소(한국산업기술진흥협회 인정서 기준)를 보유해야 합니다. 연구개발전담부서는 인정되지 않습니다.");
            assertThat(rule.sources().getFirst().excerpt()).contains(quote);
        });
        assertThat(project(body.replace("신청자격", "제출서류")).rules()).isEmpty();
        assertThat(project(body.replace("불인정", "인정")).rules()).isEmpty();
    }

    @Test void procurementScreenshotSentencesAndScopesStayExactlyTheSame() throws Exception {
        String body;
        try (var stream = getClass().getResourceAsStream("/participation-conditions/procurement-quality.txt")) {
            body = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var c = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(c.guide(), false, c.scopes());
        String quote = "최근 10년간 공공기관 계약실적이 매년 연속적으로 10억원을 초과하지 않는 기업";
        String sid = input.request().sources().stream().filter(source -> source.excerpt().contains(quote)).findFirst().orElseThrow().id();
        var response = new Response(List.of(new Statement(List.of(sid), "EXPERIENCE", quote + "이어야 합니다.", "", List.of(quote), "ELIGIBILITY")), List.of(), "READY");
        var rules = ParticipationRewriteProjection.guide(c.guide(), input, response, "READY").rules();
        assertThat(rules).extracting(ParticipationRule::text).containsExactly(
                quote + "이어야 합니다.",
                "설립 7년 이하 판로지원법 시행령 제13조의2제1항제1호에 따른 중소기업자가 지원 대상입니다.",
                "설립 7년 초과 판로지원법 시행령 제13조의2제1항제1호에 따른 중소기업자이며, 최근 10년간 공공기관 계약실적이 매년 연속적으로 10억원을 초과하지 않는 기업이 지원 대상입니다.",
                "판로지원법 시행령 제13조의2제1항제1호에 따른 중소기업자 중 설립 7년 이하 창업기업 또는 기업의 기술개발제품 공공조달 계약실적이 5억원 이하 첫걸음 기업이 지원 대상입니다.",
                "판로지원법 시행령 제13조의2 제 1항1호에 따른 중소기업자가 지원 대상입니다.");
        assertThat(rules).extracting(ParticipationRule::scope).containsExactly(null, "창업과제", "일반과제", "소액과제", "딥테크과제");
    }

    @Test void completeValidatedSummaryTakesPrecedenceOverLongerLocalSentence() {
        String quote = "□ 지원대상 : 「중소기업기본법」 제2조에 따른 중소기업";
        var c = ParticipationConditionResolver.candidatesWithScopes(quote, List.of(), null);
        var input = ParticipationRewriteProjection.input(c.guide(), false, c.scopes());
        String sid = input.request().sources().stream().filter(source -> source.excerpt().contains(quote))
                .findFirst().orElseThrow().id();
        var response = new Response(List.of(new Statement(List.of(sid), "BUSINESS_FORM",
                "중소기업이 지원 대상입니다.", "", List.of(quote), "ELIGIBILITY")), List.of(), "READY");
        var guide = ParticipationRewriteProjection.guide(c.guide(), input, response, "READY");
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.text()).isEqualTo("중소기업이 지원 대상입니다.");
            assertThat(rule.sources().getFirst().excerpt()).contains(quote);
        });
        var invalid = new Response(List.of(new Statement(List.of(sid), "BUSINESS_FORM",
                "중견기업이 지원 대상입니다.", "", List.of(quote), "ELIGIBILITY")), List.of(), "READY");
        assertThat(ParticipationRewriteProjection.guide(c.guide(), input, invalid, "READY").rules())
                .singleElement().satisfies(rule ->
                        assertThat(rule.text()).contains("중소기업").doesNotContain("중견기업"));
    }

    @Test void sameValidatedSentenceKeepsBothProgramScopes() {
        String quote = "□ 지원대상 : 「중소기업기본법」 제2조에 따른 중소기업";
        var c = ParticipationConditionResolver.candidatesWithScopes(
                "1. 성장형\n" + quote + "\n2. 도약형\n" + quote, List.of(), null);
        var input = ParticipationRewriteProjection.input(c.guide(), false, c.scopes());
        var statements = input.request().sources().stream().filter(source -> source.excerpt().contains(quote))
                .map(source -> new Statement(List.of(source.id()), "BUSINESS_FORM",
                        "중소기업이 지원 대상입니다.", "", List.of(quote), "ELIGIBILITY")).toList();
        var guide = ParticipationRewriteProjection.guide(c.guide(), input,
                new Response(statements, List.of(), "READY"), "READY");
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.scope()).isEqualTo("성장형 / 도약형");
            assertThat(rule.sources()).hasSize(2);
            assertThat(rule.text()).isEqualTo("중소기업이 지원 대상입니다.");
        });
    }
}
