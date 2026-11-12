package com.publicmonitor.backend.domain.document.service;

import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ParticipationDisplayStatementsTest {
    record Observed(int id, List<Source> sources, Map<String, List<RequirementSource>> evidence, Response cache) {}
    private final ObjectMapper mapper = new ObjectMapper();
    private ParticipationGuide raw(RequirementSource source) {
        return new ParticipationGuide(List.of(), List.of(new ParticipationRule("EVIDENCE", "원문", "", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(source))), List.of(), List.of());
    }
    private ParticipationRewriteProjection.Input input(String heading, String excerpt) {
        return ParticipationRewriteProjection.input(raw(new RequirementSource("NOTICE_BODY", null, heading, null, excerpt)), false);
    }
    private Statement cached(String code, String text, String quote, String kind) {
        return new Statement(List.of("s0"), code, text, "", List.of(quote), kind, "유형 A", "신청기관");
    }
    @Test void currentEmptyAndBrokenCachesRecoverOnlyTheThreeReadableCommonRequirements() throws Exception {
        Observed[] rows;
        try (var stream = getClass().getResourceAsStream("/participation-conditions/readable-qualification-recovery.json")) {
            rows = mapper.readValue(stream, Observed[].class);
        }
        for (var row : rows) {
            var request = new Request(row.sources(), List.of(), List.of());
            var input = new ParticipationRewriteProjection.Input(request, row.evidence(), false, null, row.sources());
            String before = mapper.writeValueAsString(request);
            var guide = ParticipationRewriteProjection.guide(new ParticipationGuide(List.of(), List.of(), List.of(), List.of()),
                    input, row.cache(), row.cache().status());
            assertThat(guide.rules()).hasSize(3).allSatisfy(rule -> {
                assertThat(rule.text()).endsWith("다.").doesNotContain("민간간기", "2인 이상", "선정 규모", "정부 지원");
                assertThat(rule.sources()).allMatch(source -> source.excerpt().contains("□ 신청 자격"));
            });
            assertThat(guide.rules()).extracting(ParticipationRule::label).containsExactlyInAnyOrder("신청 주체", "신용·재무 요건", "실적·수행 역량");
            assertThat(guide.rules().stream().filter(rule -> rule.label().equals("신용·재무 요건")).findFirst().orElseThrow().text())
                    .contains("B 이상", "BB-", "B0", "모집공고 게시일로부터 마감일 이내", "평가 및 발급");
            assertThat(guide.categories().stream().flatMap(c -> c.rows().stream()).flatMap(r -> r.entries().stream())
                    .filter(e -> e.displayStatus().equals("EVIDENCE_ONLY")).flatMap(e -> e.rule().sources().stream()))
                    .anyMatch(source -> source.excerpt().contains("60일"));
            assertThat(mapper.writeValueAsString(input.request())).isEqualTo(before);
        }
    }
    @Test void aConnectedExceptionCannotBecomeASeparateDiscardedBullet() {
        String source = "□ 신청자격\n◦ 업력 7년 이내 기업\n- 단, 대기업의 자회사는 제외";
        var restored = ParticipationDisplayStatements.prepare(input("□ 신청자격", source), List.of());
        assertThat(restored).singleElement().satisfies(item -> {
            assertThat(item.text()).contains("업력 7년", "단, 대기업의 자회사는 제외됩니다.");
            assertThat(item.supportingQuotes()).singleElement().asString().contains("- 단, 대기업의 자회사는 제외");
        });
    }
    @Test void existingMainClauseDoesNotDuplicateAndKeepsItsAttachedException() {
        for (String tail : List.of("", "\n- 단, 대기업의 자회사는 제외")) {
            var data = input("□ 신청자격", "□ 신청자격\n◦ 업력 7년 이내 기업" + tail);
            var item = new Statement(List.of("s0"), "BUSINESS_AGE", "업력은 7년 이내여야 합니다.", "",
                    List.of("업력 7년 이내 기업"), "ELIGIBILITY", null, null);
            assertThat(ParticipationDisplayStatements.prepare(data, List.of(item))).singleElement().satisfies(result -> {
                assertThat(result.text()).startsWith(item.text());
                assertThat(result.supportingQuotes()).containsAll(item.supportingQuotes());
                if (!tail.isEmpty()) assertThat(result.text()).contains("단, 대기업의 자회사는 제외됩니다.");
            });
        }
    }
    @Test void attachedNotesCannotBeMovedIntoADifferentCachedScopeOrAudience() {
        var data = input("□ 신청자격", "□ 신청자격\n◦ 업력 7년 이내 기업\n- 단, 대기업의 자회사는 제외");
        var item = cached("BUSINESS_AGE", "업력은 7년 이내여야 합니다.", "업력 7년 이내 기업", "ELIGIBILITY");
        assertThat(ParticipationDisplayStatements.prepare(data, List.of(item))).containsExactly(item);
        assertThat(ParticipationDisplayStatements.fullyCovered(data.evidence().get("s0").getFirst(), item.supportingQuotes())).isFalse();
    }
    @Test void aHeadingEndingInYoCannotTurnAnUnclearFragmentIntoASentence() {
        assertThat(ParticipationDisplayStatements.prepare(input("□ 신청자격", "□ 신청자격\n전문 기관\n3\n사업 개요"), List.of())).isEmpty();
    }
    @Test void fundingAndPreferenceClausesAreNeverSynthesizedAsMandatoryEligibility() {
        for (String clause : List.of("중소기업 지원비율 50% 이하", "중소기업 지원금액 3억원 이하", "중소기업 선정개수 14개",
                "(우대) 특허 보유기업", "특허 보유기업 가점", "중소기업 지원기간 6개월")) {
            assertThat(ParticipationDisplayStatements.prepare(input("□ 신청자격", "□ 신청자격\n◦ " + clause), List.of())).isEmpty();
        }
    }
    @Test void readableSingleCachedClauseKeepsItsClassificationKindAndScopeOutsideQualificationHeadings() {
        var input = input("지원대상", "업력 3년 이하");
        var item = cached("BUSINESS_AGE", "업력 3년 이하", "업력 3년 이하", "ELIGIBILITY");
        assertThat(ParticipationDisplayStatements.prepare(input, List.of(item))).singleElement().satisfies(result -> {
            assertThat(result.text()).isEqualTo("업력 3년 이하이어야 합니다.");
            assertThat(result.criterion()).isEqualTo(item.criterion());
            assertThat(result.scope()).isEqualTo(item.scope());
            assertThat(result.audience()).isEqualTo(item.audience());
            assertThat(result.supportingQuotes()).isEqualTo(item.supportingQuotes());
        });
        var preference = cached("BUSINESS_FORM", "중소기업 우대", "중소기업 우대", "PREFERENCE");
        assertThat(ParticipationDisplayStatements.prepare(input("우대사항", "중소기업 우대"), List.of(preference)))
                .singleElement().satisfies(result -> {
                    assertThat(result.kind()).isEqualTo("PREFERENCE");
                    assertThat(result.text()).isEqualTo("중소기업 우대합니다.").doesNotContain("해야");
                });
    }
    @Test void completedModelSentencesArePreservedWithoutApplyingRestorationHeuristics() {
        for (String text : List.of("신청기업은 중소기업이어야 합니다", "법인(중소기업(예외 포함))이어야 합니다.")) {
            var item = cached("BUSINESS_FORM", text, "중소기업", "ELIGIBILITY");
            assertThat(ParticipationDisplayStatements.prepare(input("지원조건", "중소기업"), List.of(item)))
                    .containsExactly(item);
        }
    }
    @Test void duplicateMergingDoesNotCombineDifferentKindsOrCriteria() {
        String text = "중소기업이어야 합니다.";
        var a = cached("BUSINESS_FORM", text, "중소기업", "ELIGIBILITY");
        var b = cached("BUSINESS_FORM", text, "중소기업", "PREFERENCE");
        var c = cached("EXPERIENCE", text, "중소기업", "ELIGIBILITY");
        assertThat(ParticipationDisplayStatements.prepare(input("지원조건", "중소기업"), List.of(a, a, b, c)))
                .containsExactly(a, b, c);
    }
    @Test void oneClaimCannotMarkTheEntireSourceAsCovered() {
        var source = new RequirementSource("NOTICE_BODY", null, "□ 신청자격", null,
                "□ 신청자격\n◦ 중소기업\n- 신용등급 B 이상");
        assertThat(ParticipationDisplayStatements.fullyCovered(source, List.of("- 신용등급 B 이상"))).isFalse();
        assertThat(ParticipationDisplayStatements.fullyCovered(source, List.of("◦ 중소기업", "- 신용등급 B 이상"))).isTrue();
    }
}
