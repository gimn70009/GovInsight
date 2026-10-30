package com.publicmonitor.backend.domain.document.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import static com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;

class ParticipationStructuredConditionsTest {
    private String fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/participation-conditions/smart-manufacturing-targets.json")) {
            return new ObjectMapper().readTree(input).get(name).asText();
        }
    }

    private ParticipationGuide project(String body) {
        var candidates = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        return ParticipationRewriteProjection.guide(candidates.guide(), input, Response.unavailable(input.request()), "UNAVAILABLE");
    }

    @Test void actualFourColumnTargetsBecomeFourIndependentConditionsWithTheirOwnTitles() throws Exception {
        String body = fixture("structuredTable");
        var guide = project(body);
        assertThat(guide.rules()).hasSize(4);
        assertThat(guide.rules()).extracting(ParticipationRule::scope).containsExactly(
                "제조특화 수작업 실증", "위험·유해 공정 실증", "민관협력 기술 실증", "통합실증");
        assertThat(guide.rules().get(0).text()).contains("수출주력", "수작업 공정").doesNotContain("뿌리", "피지컬AI");
        assertThat(guide.rules().get(1).text()).contains("뿌리 중소기업").doesNotContain("수출주력", "벤처");
        assertThat(guide.rules().get(2).text()).contains("벤처·창업기업", "우수 R&D").doesNotContain("수출주력", "복수 공정");
        assertThat(guide.rules().get(3).text()).contains("복수 공정").doesNotContain("수출주력", "벤처");
        assertThat(guide.rules()).allSatisfy(rule -> assertThat(rule.sources()).singleElement()
                .satisfies(source -> assertThat(source.excerpt()).isEqualTo(body)));
        guide.categories().stream().flatMap(c -> c.rows().stream()).flatMap(r -> r.entries().stream())
                .filter(e -> "VERIFIED".equals(e.displayStatus())).forEach(entry -> {
                    assertThat(entry.supportingQuotes()).hasSize(1);
                    assertThat(body).contains(entry.supportingQuotes().getFirst());
                    assertThat(entry.supportingQuotes().getFirst()).doesNotContain("\t");
                });
    }

    @Test void missingColumnHeadersOrBrokenCellCountsNeverAssignOneTargetsConditionToAnother() {
        for (String table : List.of(
                "구분\t성장형\t확장형\n지원대상\t제조업을 영위하는 중소기업",
                "구분\t성장형\t\n지원대상\t제조업을 영위하는 중소기업\t특허를 보유한 중소기업",
                "구분\t성장형\t성장형\n지원대상\t제조업을 영위하는 중소기업\t특허를 보유한 중소기업",
                "지원대상\t제조업을 영위하는 중소기업\t특허를 보유한 중소기업")) {
            assertThat(project("□ 신청자격\n[표]\n" + table + "\n[/표]").rules()).isEmpty();
        }
    }

    @Test void flattenedPdfTableRemainsEvidenceWithoutGuessingItsColumnLayout() throws Exception {
        var guide = project(fixture("flattenedTable"));
        assertThat(guide.rules()).isEmpty();
        assertThat(guide.categories().stream().flatMap(c -> c.rows().stream()).flatMap(r -> r.entries().stream()))
                .anyMatch(entry -> "EVIDENCE_ONLY".equals(entry.displayStatus()) && !entry.rule().sources().isEmpty());
    }

    @Test void twoExplicitApplicantRolesRetainTheirOwnConditionsAndAudience() throws Exception {
        for (String name : List.of("roleTargets", "wrappedRoleTargets")) {
            var guide = project(fixture(name));
            assertThat(guide.rules()).hasSize(2);
            assertThat(guide.rules()).extracting(ParticipationRule::audience).containsExactly("도입기업", "공급기업");
            assertThat(guide.rules().get(0).text()).startsWith("도입기업은 ").contains("중간1", "또는")
                    .doesNotContain("등록된", "보유기업 또는 협업체");
            assertThat(guide.rules().get(1).text()).startsWith("공급기업은 ").contains("등록된", "또는 협업체")
                    .doesNotContain("중간1");
            assertThat(guide.rules()).allSatisfy(rule -> assertThat(rule.label()).isEqualTo("신청 주체"));
        }
    }

    @Test void sharedConsortiumDutyIsNotDroppedWhenDividingApplicantRoles() throws Exception {
        assertThat(project(fixture("consortiumTargets")).rules()).isEmpty();
        assertThat(project("□ 지원대상\n- (도입기업) 국내 제조기업\n※ 단, 대기업은 제외\n- (공급기업) 기술 보유 중소기업").rules())
                .noneMatch(rule -> "도입기업".equals(rule.audience()));
    }

    @Test void ordinaryPdfWordSpacingIsNotMistakenForSeveralIndependentCells() throws Exception {
        var guide = project(fixture("ordinaryPdfProse"));
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.text()).isEqualTo("제조데이터를 수집 중인 국내 중소·중견 제조기업이 지원 대상입니다.");
            assertThat(rule.label()).isEqualTo("신청 주체");
        });
    }

    @Test void cachedCombinedTableOrRoleSentenceCannotBypassStructuralChecks() throws Exception {
        String row = fixture("structuredTable").lines().filter(line -> line.startsWith("지원대상\t")).findFirst().orElseThrow();
        String quote = row.substring(row.indexOf('\t') + 1);
        assertThat(ParticipationFocusedText.prepare("BUSINESS_FORM", quote.replace('\t', ' ') + "이 지원 대상입니다.",
                quote, "신청자격", "ELIGIBILITY")).isNull();
        String roles = "(도입기업) 제조업 중소기업\n(공급기업) 솔루션 보유 중소기업";
        assertThat(ParticipationFocusedText.prepare("BUSINESS_FORM", roles.replace('\n', ' ') + "이 지원 대상입니다.",
                roles, "신청자격", "ELIGIBILITY")).isNull();
    }

    @Test void sameEntityWordsInACompleteProseClauseAreNotSplitOrRejected() {
        String quote = "제조업 중소기업 또는 기술을 보유한 중견기업";
        assertThat(project("□ 지원대상\n" + quote).rules()).singleElement()
                .satisfies(rule -> assertThat(rule.text()).contains(quote));
        assertThat(ParticipationFocusedText.prepare("BUSINESS_FORM", quote + "이 지원 대상입니다.", quote,
                "신청자격", "ELIGIBILITY")).isNotNull();
    }
    @Test void tabsBetweenExplicitAlternativesOrConjunctionsRemainOneCompleteClause() {
        for (String separator : List.of("\t또는 ", "\t또는\t", "\t및 ")) {
            String quote = "제조업 중소기업" + separator + "기술을 보유한 중견기업";
            String sentence = quote.replaceAll("\\s+", " ") + "이 지원 대상입니다.";
            assertThat(ParticipationConditionStructure.multipleTargets(quote)).isFalse();
            assertThat(ParticipationFocusedText.prepare("BUSINESS_FORM", sentence, quote, "신청자격", "ELIGIBILITY"))
                    .isNotNull();
            assertThat(project("□ 지원대상\n" + quote).rules()).singleElement()
                    .satisfies(rule -> assertThat(rule.text()).isEqualTo(sentence));
        }
    }

    @Test void tableSplittingCannotDropSharedExclusionRowsOrFollowingFootnotes() {
        String table = "[표]\n구분\t성장형\t확장형\n지원대상\t제조업 중소기업\t특허 보유 중소기업\n";
        for (String suffix : List.of("지원제외\t휴업 기업\t체납 기업\n[/표]",
                "[/표]\n※ 단, 대기업은 제외", "[/표]\n* 참여기업은\n공통 요건을 충족해야 함")) {
            assertThat(project("□ 신청자격\n" + table + suffix).rules()).isEmpty();
        }
        assertThat(project("□ 신청자격\n" + table + "[/표]\n※ 세부 안내는 개별 공고 확인").rules()).hasSize(2);
    }

    @Test void cachedCellSentenceKeepsItsActualColumnHeaderAndOtherCells() throws Exception {
        String body = fixture("structuredTable");
        var candidates = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        String quote = ParticipationConditionStructure.tableTargets(body).getFirst().quote();
        String sourceId = input.request().sources().stream().filter(source -> source.excerpt().contains(quote))
                .findFirst().orElseThrow().id();
        String sentence = quote + "이 지원 대상입니다.";
        var response = new Response(List.of(new Statement(List.of(sourceId), "BUSINESS_FORM", sentence, "",
                List.of(quote), "ELIGIBILITY")), List.of(), "READY");
        var guide = ParticipationRewriteProjection.guide(candidates.guide(), input, response, "READY");
        assertThat(guide.rules()).hasSize(4);
        assertThat(guide.rules()).filteredOn(rule -> rule.text().equals(sentence)).singleElement()
                .satisfies(rule -> assertThat(rule.scope()).isEqualTo("제조특화 수작업 실증"));
    }

    @Test void cachedIdenticalProseForDifferentRolesKeepsBothAudiences() {
        String buyer = "- (도입기업) 중소기업";
        String supplier = "- (공급기업) 중소기업";
        var candidates = ParticipationConditionResolver.candidatesWithScopes("□ 지원대상\n" + buyer + "\n" + supplier,
                List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        var statements = List.of(buyer, supplier).stream().map(quote -> {
            String id = input.request().sources().stream().filter(source -> source.excerpt().contains(quote))
                    .findFirst().orElseThrow().id();
            return new Statement(List.of(id), "BUSINESS_FORM", "중소기업이 지원 대상입니다.", "", List.of(quote), "ELIGIBILITY");
        }).toList();
        var guide = ParticipationRewriteProjection.guide(candidates.guide(), input,
                new Response(statements, List.of(), "READY"), "READY");
        assertThat(guide.rules()).hasSize(2);
        assertThat(guide.rules()).extracting(ParticipationRule::audience).containsExactly("도입기업", "공급기업");
    }

}
