package com.publicmonitor.backend.domain.document.service;

import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ParticipationCachedEligibilityTest {
    record Sample(List<Source> sources, Map<String, List<RequirementSource>> evidence, Response cache, Integer id) {}
    private final ObjectMapper mapper = new ObjectMapper();
    private Sample sample(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/participation-conditions/" + name + ".json")) {
            return mapper.readValue(stream, Sample.class);
        }
    }
    private ParticipationGuide raw(List<RequirementSource> sources) {
        return new ParticipationGuide(List.of(), sources.stream().map(source ->
                new ParticipationRule("EVIDENCE", "원문", "", null, null, List.of(), List.of(), List.of(), List.of(), List.of(source))).toList(),
                List.of(), List.of());
    }
    private List<ParticipationCriterionEntry> entries(ParticipationGuide guide) {
        return guide.categories().stream().flatMap(c -> c.rows().stream()).flatMap(r -> r.entries().stream()).toList();
    }
    private ParticipationGuide project(Sample sample) {
        var input = new ParticipationRewriteProjection.Input(new Request(sample.sources(), List.of(), List.of()),
                sample.evidence(), false, null, sample.sources());
        return ParticipationRewriteProjection.guide(raw(List.of()), input, sample.cache(), sample.cache().status());
    }
    @Test void legacyFragmentsKeepEvidenceAndRequirePresentationReview() throws Exception {
        // These fixtures contain raw fragments: retain evidence, but do not label the presentation verified.
        for (String name : List.of("investment-cached-eligibility", "inline-qualification-recovery")) {
            var sample = sample(name);
            String before = mapper.writeValueAsString(sample);
            var guide = project(sample);
            assertThat(guide.rules()).extracting(ParticipationRule::text)
                    .containsExactlyElementsOf(sample.cache().statements().stream().map(Statement::text).toList());
            assertThat(entries(guide).stream().filter(entry -> entry.displayStatus().equals("REVIEW_REQUIRED")))
                    .hasSize(sample.cache().statements().size());
            assertThat(guide.rules()).allSatisfy(rule -> assertThat(rule.sources()).isNotEmpty());
            assertThat(mapper.writeValueAsString(sample)).isEqualTo(before);
        }
    }
    private ParticipationGuide one(String quote, String text, String kind) {
        var source = new RequirementSource("NOTICE_BODY", null, "원문", null, quote);
        var request = new Source("s0", quote, "원문", List.of("BUSINESS_FORM"));
        return project(new Sample(List.of(request), Map.of("s0", List.of(source)), new Response(List.of(
                new Statement(List.of("s0"), "BUSINESS_FORM", text, "", List.of(quote), kind, "일반형", "신청기업")), List.of(), "READY"), null));
    }
    @Test void cachedWordingKeepsScopeAndQuotesButCannotInventAnApplicantAcrossFormatting() {
        for (String title : List.of("지원대상", "신청 자격", "참여 요건", "모집 대상")) {
            for (String prefix : List.of("", "□ ", "■ ")) {
                for (String colon : List.of(": ", "：")) {
                    String clause = "설립 7년 이내 중소기업 또는 비영리기관";
                    String quote = prefix + title + colon + clause;
                    for (String text : List.of(quote, clause)) {
                        var guide = one(quote, text, "ELIGIBILITY");
                        assertThat(guide.rules()).singleElement().satisfies(rule -> {
                            assertThat(rule.text()).isEqualTo(text);
                            assertThat(rule.scope()).isEqualTo("일반형");
                            assertThat(rule.audience()).isNull();
                        });
                        assertThat(entries(guide)).singleElement().satisfies(entry ->
                                assertThat(entry.supportingQuotes()).containsExactly(quote));
                    }
                }
            }
        }
    }
    @Test void citationValidationDoesNotPretendToDecideWhetherLegacyFragmentsAreConditions() {
        for (String text : List.of("□ 지원대상 : 중소기업", "법인명(성명) 예시산업(대표자)",
                "□ 문의처 : 사업 운영기관", "[표] 자격\t국내 기업 [/표]", "요건을 갖춘 기관(예외 포함")) {
            assertThat(one(text, text, "ELIGIBILITY").rules()).singleElement()
                    .satisfies(rule -> assertThat(rule.text()).isEqualTo(text));
        }
        // A matching quote proves provenance, not that the model selected or summarized it correctly.
        assertThat(one("중소기업 또는 비영리기관", "중소기업", "ELIGIBILITY").rules())
                .singleElement().satisfies(rule -> assertThat(rule.text()).isEqualTo("중소기업"));
    }
    @Test void repeatedReadsAndRestartsReuseTheExactCacheWithoutPaidCallsOrDatabaseWrites() throws Exception {
        var sample = sample("investment-cached-eligibility");
        var raw = raw(sample.evidence().values().stream().flatMap(List::stream).distinct().toList());
        var input = ParticipationRewriteProjection.input(raw, false);
        var ids = new HashMap<String, String>();
        sample.evidence().forEach((id, sources) -> ids.put(id, input.evidence().entrySet().stream()
                .filter(entry -> entry.getValue().contains(sources.getFirst())).findFirst().orElseThrow().getKey()));
        var response = new Response(sample.cache().statements().stream().map(item -> new Statement(
                item.sourceIds().stream().map(ids::get).toList(), item.criterion(), item.text(), item.groupLabel(),
                item.supportingQuotes(), item.kind(), item.scope(), item.audience())).toList(), List.of(), "READY");
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var version = DocumentVersion.create(null, 1, "투자지원 공고", "원문", "a".repeat(64), null, 0, null);
        ReflectionTestUtils.setField(version, "id", 1L);
        String json = mapper.writeValueAsString(response);
        for (int restart = 0; restart < 2; restart++) {
            var service = new ParticipationRewriteService(client, store, mapper);
            try {
                String key = service.fingerprint(input.request());
                ReflectionTestUtils.setField(version, "participationRewriteKey", key);
                ReflectionTestUtils.setField(version, "participationRewriteJson", json);
                for (int read = 0; read < 3; read++) {
                    assertThat(service.resolve(version, raw, false).rules()).hasSize(sample.cache().statements().size());
                    assertThat(version.getParticipationRewriteKey()).isEqualTo(key);
                    assertThat(version.getParticipationRewriteJson()).isEqualTo(json);
                }
            } finally { service.close(); }
        }
        verifyNoInteractions(client, store);
    }
}
