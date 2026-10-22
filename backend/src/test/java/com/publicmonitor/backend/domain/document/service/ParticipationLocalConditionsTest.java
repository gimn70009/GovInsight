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
}
