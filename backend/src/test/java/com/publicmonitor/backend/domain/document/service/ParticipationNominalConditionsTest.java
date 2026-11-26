package com.publicmonitor.backend.domain.document.service;

import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ParticipationNominalConditionsTest {
    record Sample(String name, List<Source> sources, Map<String, List<RequirementSource>> evidence,
                  Response cache, int expected) {}
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void realGroundedNominalConditionsSurviveWithoutRewritingClaimsOrCache() throws Exception {
        Sample[] samples;
        try (var stream = getClass().getResourceAsStream("/participation-conditions/nominal-cached-conditions.json")) {
            samples = mapper.readValue(stream, Sample[].class);
        }
        for (var sample : samples) {
            String before = mapper.writeValueAsString(sample);
            var input = new ParticipationRewriteProjection.Input(new Request(sample.sources(), List.of(), List.of()),
                    sample.evidence(), false, null, sample.sources());
            var guide = ParticipationRewriteProjection.guide(new ParticipationGuide(List.of(), List.of(), List.of(), List.of()),
                    input, sample.cache(), "READY");
            assertThat(guide.rules()).as(sample.name()).hasSize(sample.expected());
            assertThat(guide.rules()).extracting(ParticipationRule::text)
                    .containsExactlyElementsOf(sample.cache().statements().stream().map(Statement::text).toList());
            assertThat(guide.rules()).allSatisfy(rule -> {
                assertThat(rule.details()).doesNotContain("[]");
                if (rule.scope() != null) assertThat(rule.scope()).doesNotStartWith("별표 ").doesNotContain(" 제27조");
                assertThat(rule.sources()).isNotEmpty();
            });
            assertThat(mapper.writeValueAsString(sample)).isEqualTo(before);
        }
    }

    @Test void invalidStructureOrSourceStillCannotReachTheDisplay() {
        var source = new Source("s0", "지역 내에서 업무를 수행하는 비영리 기관", "관련 조건", List.of("BUSINESS_FORM"));
        var input = new ParticipationRewriteProjection.Input(new Request(List.of(source), List.of(), List.of()),
                Map.of("s0", List.of(new RequirementSource("NOTICE_BODY", null, "관련 조건", null, source.excerpt()))), false, null, List.of(source));
        for (var item : List.of(
                new Statement(List.of("unknown"), "BUSINESS_FORM", source.excerpt(), "", List.of(source.excerpt()), "ELIGIBILITY"),
                new Statement(List.of("s0"), "BUSINESS_FORM", source.excerpt(), "", List.of("다른 지역의 기관"), "ELIGIBILITY"),
                new Statement(List.of("s0"), "BUSINESS_FORM", " ", "", List.of(source.excerpt()), "ELIGIBILITY"),
                new Statement(List.of("s0"), "UNKNOWN", source.excerpt(), "", List.of(source.excerpt()), "ELIGIBILITY"),
                new Statement(List.of("s0"), "BUSINESS_FORM", source.excerpt(), "", List.of(source.excerpt()), "UNKNOWN"),
                new Statement(List.of("s0"), "BUSINESS_FORM", source.excerpt(), "", List.of(), "ELIGIBILITY"))) {
            var guide = ParticipationRewriteProjection.guide(new ParticipationGuide(List.of(), List.of(), List.of(), List.of()),
                    input, new Response(List.of(item), List.of(), "READY"), "READY");
            assertThat(guide.rules()).isEmpty();
        }
    }
}
