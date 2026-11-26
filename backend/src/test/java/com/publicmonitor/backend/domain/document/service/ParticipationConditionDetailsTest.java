package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ParticipationConditionDetailsTest {
    private static final String MAIN = "공급망안정품목 또는 전략물자를 생산하는 중소·중견기업이 신청할 수 있습니다.";
    private static final String DETAIL = "전략물자는 대외무역법에 따라 지정·고시되는 품목입니다.";
    private final ParticipationFocusProjectionTest helper = new ParticipationFocusProjectionTest();

    private Statement parent(List<Detail> details) {
        return new Statement(List.of("s0"), "SECTOR", MAIN, "지원대상", List.of(MAIN),
                "ELIGIBILITY", null, null, details);
    }

    @Test void explanatorySourcesAreAttachedToOneParentAndSurviveCacheSerialization() {
        var statement = parent(List.of(new Detail(List.of("s1"), DETAIL, List.of(DETAIL))));
        var mapper = JsonMapper.builder().build();
        var response = new Response(List.of(statement), List.of(), "READY");
        var restored = mapper.readValue(mapper.writeValueAsString(response), Response.class);
        var guide = helper.project(helper.raw(MAIN, DETAIL), restored);
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.text()).isEqualTo(MAIN);
            assertThat(rule.details()).containsExactly(DETAIL);
            assertThat(rule.sources()).extracting(RequirementSource::excerpt).containsExactly(MAIN, DETAIL);
        });
        assertThat(helper.entries(guide).stream().filter(e -> "VERIFIED".equals(e.displayStatus())))
                .singleElement().satisfies(entry -> assertThat(entry.supportingQuotes()).containsExactly(MAIN, DETAIL));
    }

    @Test void invalidExplanationsCannotEraseParentOrExposeUnsupportedText() {
        var guide = helper.project(helper.raw(MAIN, DETAIL), new Response(List.of(parent(List.of(
                new Detail(List.of("missing"), "없는 설명입니다.", List.of(DETAIL)),
                new Detail(List.of("s1"), "거짓 설명입니다.", List.of("없는 인용")),
                new Detail(List.of("s1"), DETAIL, List.of(DETAIL))))), List.of(), "READY"));
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.text()).isEqualTo(MAIN);
            assertThat(rule.details()).containsExactly(DETAIL);
        });
    }

    @Test void independentRequirementsAndExclusionsStaySeparate() {
        String age = "업력이 7년 이내여야 합니다.", exclusion = "신용불량 기업은 지원에서 제외됩니다.";
        var guide = helper.project(helper.raw(MAIN, DETAIL, age, exclusion), new Response(List.of(
                parent(List.of(new Detail(List.of("s1"), DETAIL, List.of(DETAIL)))),
                new Statement(List.of("s2"), "BUSINESS_AGE", age, "", List.of(age), "ELIGIBILITY", "성장형", null),
                new Statement(List.of("s3"), "CREDIT", exclusion, "", List.of(exclusion), "EXCLUSION")), List.of(), "READY"));
        assertThat(guide.rules()).extracting(ParticipationRule::section).containsExactly("BASIC", "BASIC", "EXCLUSION");
        assertThat(guide.rules().get(1).details()).isEmpty();
        assertThat(guide.rules().get(1).scope()).isEqualTo("성장형");
    }

    @Test void oldFlatCacheRemainsReadableWithoutFabricatedGrouping() {
        String json = "{\"source_ids\":[\"s0\"],\"criterion\":\"SECTOR\",\"text\":\"기업입니다.\",\"group_label\":\"\",\"supporting_quotes\":[\"기업\"],\"kind\":\"ELIGIBILITY\"}";
        assertThat(JsonMapper.builder().build().readValue(json, Statement.class).details()).isEmpty();
    }
}
