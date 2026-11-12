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
    @Test void investmentCacheDisplaysOnlyActualApplicantRequirementWithoutChangingStoredContent() throws Exception {
        var sample = sample("investment-cached-eligibility");
        String before = mapper.writeValueAsString(sample);
        assertThat(sample.cache().statements()).hasSize(8);
        var guide = project(sample);
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.label()).isEqualTo("신청 주체");
            assertThat(rule.section()).isEqualTo("BASIC");
            assertThat(rule.text()).startsWith("신청 대상은 ").endsWith("중소·중견 소부장 기업입니다.")
                    .contains("공급망안정품목", "또는", "전략물자", "생산하고 있거나 생산 예정인");
            assertThat(rule.sources()).containsExactlyElementsOf(sample.evidence().get("s6"));
        });
        assertThat(entries(guide)).noneMatch(entry -> "REVIEW_REQUIRED".equals(entry.displayStatus()));
        assertThat(entries(guide).stream().filter(entry -> entry.displayStatus().equals("VERIFIED"))).hasSize(1);
        assertThat(mapper.writeValueAsString(sample)).isEqualTo(before);
    }
    @Test void technologySharingApplicantSurvivesButDefinitionsDoNotBecomePreferences() throws Exception {
        var sample = sample("inline-qualification-recovery");
        var guide = project(sample);
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.text()).isEqualTo("신청 대상은 중소·중견기업입니다.");
            assertThat(rule.section()).isEqualTo("BASIC");
            assertThat(rule.sources()).singleElement().satisfies(source ->
                    assertThat(source.excerpt()).contains("중소기업기본법", "중견기업 성장촉진"));
        });
        assertThat(entries(guide).stream().filter(entry -> entry.displayStatus().equals("VERIFIED")))
                .singleElement().satisfies(entry -> assertThat(entry.supportingQuotes())
                        .containsExactly("□ 지원자격 : 중소·중견기업"));
    }
    private ParticipationGuide one(String quote, String text, String kind) {
        var source = new RequirementSource("NOTICE_BODY", null, "원문", null, quote);
        var request = new Source("s0", quote, "원문", List.of("BUSINESS_FORM"));
        return project(new Sample(List.of(request), Map.of("s0", List.of(source)), new Response(List.of(
                new Statement(List.of("s0"), "BUSINESS_FORM", text, "", List.of(quote), kind, "일반형", "신청기업")), List.of(), "READY"), null));
    }
    @Test void inlineApplicantLabelsWorkAcrossFormattingWithoutReclassifyingOrLosingQuotes() {
        for (String title : List.of("지원대상", "신청 자격", "참여 요건", "모집 대상")) {
            for (String prefix : List.of("", "□ ", "■ ")) {
                for (String colon : List.of(": ", "：")) {
                    String clause = "설립 7년 이내 중소기업 또는 비영리기관";
                    String quote = prefix + title + colon + clause;
                    for (String text : List.of(quote, clause)) {
                        var guide = one(quote, text, "ELIGIBILITY");
                        assertThat(guide.rules()).singleElement().satisfies(rule -> {
                            assertThat(rule.text()).isEqualTo("신청 대상은 " + clause + "입니다.");
                            assertThat(rule.scope()).isEqualTo("일반형");
                            assertThat(rule.audience()).isEqualTo("신청기업");
                        });
                        assertThat(entries(guide)).singleElement().satisfies(entry ->
                                assertThat(entry.supportingQuotes()).containsExactly(quote));
                    }
                }
            }
        }
    }
    @Test void headingsProceduresFundingAndAmbiguousFragmentsAreNotApplicantClaims() {
        for (String quote : List.of("□ 추진체계 : 전담기관", "□ 지원범위 : 건설 및 토지 매입 비용", "□ 관련 규정 : 지원금 운영 규정",
                "□ 문의처 : 사업 운영기관", "가. 신청서 제출 전 확인 절차 필요", "□ 지원자격 : 중소기업\n※ 예외: 별도 확인",
                "□ 지원대상 : 중소기업\n□ 지원내용 : 지원금", "□ 지원대상 : [표] 기업\t기관 [/표]")) {
            assertThat(one(quote, quote, "ELIGIBILITY").rules()).isEmpty();
        }
        assertThat(one("□ 지원대상 : 중소기업 또는 비영리기관", "중소기업", "ELIGIBILITY").rules()).isEmpty();
        assertThat(one("지원 대상", "□ 추진체계 : 사업 운영기관", "ELIGIBILITY").rules()).isEmpty();
        assertThat(one("* 중소기업 : 관계 법령 제2조에 의한 기업", "중소기업 : 관계 법령 제2조에 의한 기업", "PREFERENCE").rules()).isEmpty();
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
                    assertThat(service.resolve(version, raw, false).rules()).hasSize(1);
                    assertThat(version.getParticipationRewriteKey()).isEqualTo(key);
                    assertThat(version.getParticipationRewriteJson()).isEqualTo(json);
                }
            } finally { service.close(); }
        }
        verifyNoInteractions(client, store);
    }
}
