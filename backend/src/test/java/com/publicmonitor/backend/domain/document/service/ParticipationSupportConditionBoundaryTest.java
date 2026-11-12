package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ParticipationSupportConditionBoundaryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    record Observed(int versionId, Source source, List<RequirementSource> evidence, Statement statement, boolean frozen) {}

    private List<Observed> observed() throws Exception {
        try (var stream = getClass().getResourceAsStream("/participation-conditions/support-condition-boundary.json")) {
            return Arrays.asList(mapper.readValue(stream, Observed[].class));
        }
    }

    private ParticipationGuide raw(RequirementSource... sources) {
        var rules = Arrays.stream(sources).map(source -> new ParticipationRule("EVIDENCE", "원문", "", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(source))).toList();
        return new ParticipationGuide(List.of(), rules, List.of(), List.of());
    }

    private ParticipationRewriteProjection.Input capturedInput(List<Observed> samples) {
        var evidence = new LinkedHashMap<String, List<RequirementSource>>();
        samples.forEach(sample -> evidence.put(sample.source().id(), sample.evidence()));
        var catalog = ParticipationRewriteProjection.input(raw(), false).request().criteria();
        var frozen = new Request(samples.stream().filter(Observed::frozen).map(Observed::source).toList(), catalog, List.of());
        return new ParticipationRewriteProjection.Input(frozen, evidence, false, null,
                samples.stream().map(Observed::source).toList());
    }

    @Test void actualFundingSectionIsExcludedOnlyFromNewSupplementalInputWhileMixedTargetTableRemains() throws Exception {
        var samples = observed();
        var mixed = samples.stream().filter(sample -> sample.source().id().equals("s44")).findFirst().orElseThrow();
        var funding = samples.stream().filter(sample -> !sample.frozen()).findFirst().orElseThrow();
        var scale = samples.stream().filter(sample -> sample.versionId() == 3).findFirst().orElseThrow();
        assertThat(ParticipationSourceSelection.explicit(mixed.evidence().getFirst())).isTrue();
        assertThat(ParticipationSourceSelection.explicit(funding.evidence().getFirst())).isFalse();
        assertThat(ParticipationSourceSelection.compatibleCandidate(funding.evidence().getFirst())).isTrue();
        assertThat(ParticipationSourceSelection.explicit(scale.evidence().getFirst())).isFalse();
        var input = capturedInput(samples);
        var before = mapper.writeValueAsString(input.request());
        var outbound = ParticipationSourceSelection.select(input).outbound(input);
        assertThat(outbound.sources()).extracting(Source::id).containsExactly("s44", "s15");
        assertThat(outbound.sources().getFirst().excerpt()).contains("지원대상", "전문연구기관", "중소 제조기업 등");
        assertThat(mapper.writeValueAsString(input.request())).isEqualTo(before);
        assertThat(input.availableSources()).extracting(Source::id).contains(funding.source().id());
        assertThat(input.evidence().get(funding.source().id())).containsExactlyElementsOf(funding.evidence());
    }

    @Test void copiedFundingTablesAndSelectionHeadingsRemainRawEvidenceInsteadOfQualificationSentences() throws Exception {
        var samples = observed();
        var input = capturedInput(samples);
        var response = new Response(samples.stream().map(Observed::statement).toList(), List.of(), "READY");
        var guide = ParticipationRewriteProjection.guide(raw(), input, response, "READY");
        assertThat(guide.rules()).isEmpty();
        assertThat(guide.categories().stream().flatMap(category -> category.rows().stream()).flatMap(row -> row.entries().stream())
                .flatMap(entry -> entry.rule().sources().stream()).distinct())
                .containsExactlyInAnyOrderElementsOf(samples.stream().flatMap(sample -> sample.evidence().stream())
                        .filter(ParticipationSourceSelection.select(input)::includes).distinct().toList());
    }

    @Test void oldSupplementalEligibilityUnderGenericSupportHeadingRetainsItsIdAndPaidCache() throws Exception {
        var oldSource = new RequirementSource("NOTICE_BODY", null, "□ 지원조건", null,
                "□ 지원조건\n전담부처의 추천을 받은 기관");
        var data = raw(oldSource);
        var input = ParticipationRewriteProjection.input(data, false);
        String oldId = "u0b3460a93af500df4d7499a70d637c3fe1d56caeb72435b9e5842a869c133c58";
        assertThat(input.request().sources()).isEmpty();
        assertThat(input.availableSources()).extracting(Source::id).containsExactly(oldId);
        assertThat(input.evidence().get(oldId)).containsExactly(oldSource);
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources()).isEmpty();
        var response = new Response(List.of(new Statement(List.of(oldId), "BUSINESS_FORM",
                "전담부처의 추천을 받은 기관이 신청할 수 있습니다.", "□ 지원조건",
                List.of("전담부처의 추천을 받은 기관"), "ELIGIBILITY")), List.of(), "READY");
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = DocumentVersion.create(null, 1, "공고", oldSource.excerpt(), "a".repeat(64), null, 0, null);
            ReflectionTestUtils.setField(version, "id", 190L);
            ReflectionTestUtils.setField(version, "participationRewriteKey", service.fingerprint(input.request()));
            ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(response));
            var guide = service.resolve(version, data, false);
            assertThat(guide.rewriteStatus()).isEqualTo("READY");
            assertThat(guide.rules()).singleElement().satisfies(rule -> {
                assertThat(rule.text()).isEqualTo(response.statements().getFirst().text());
                assertThat(rule.sources()).containsExactly(oldSource);
            });
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void onlyGenericFundingSourcesRequireNoNewClientOrStoreCall() throws Exception {
        var sample = observed().stream().filter(item -> !item.frozen()).findFirst().orElseThrow();
        var data = raw(sample.evidence().getFirst());
        var input = ParticipationRewriteProjection.input(data, false);
        assertThat(input.availableSources()).hasSize(1);
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources()).isEmpty();
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = DocumentVersion.create(null, 1, "공고", sample.source().excerpt(), "a".repeat(64), null, 0, null);
            ReflectionTestUtils.setField(version, "id", 191L);
            var guide = service.resolve(version, data, false);
            assertThat(guide.rewriteStatus()).isEqualTo("READY");
            assertThat(guide.rules()).isEmpty();
            assertThat(guide.categories().stream().flatMap(category -> category.rows().stream())
                    .flatMap(row -> row.entries().stream()).flatMap(entry -> entry.rule().sources().stream()).distinct())
                    .containsExactly(sample.evidence().getFirst());
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void supplementalMixedTableRetainsAnExplicitApplicantColumnWithoutChangingFrozenRequest() {
        var source = new RequirementSource("NOTICE_BODY", null, "□ 지원조건", null,
                "□ 지원조건\n[표]\n구분\t신청자격\t기간\n유형 A\t대학 또는 연구기관\t6개월\n[/표]");
        var input = ParticipationRewriteProjection.input(raw(source), false);
        assertThat(input.request().sources()).isEmpty();
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources()).singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).startsWith("u");
                    assertThat(item.excerpt()).isEqualTo(source.excerpt());
                });
        assertThat(input.request().sources()).isEmpty();
    }

    @Test void explicitBusinessApplicationHeadingAddsAnUnknownApplicantTypeWithoutChangingFrozenIds() {
        var old = new RequirementSource("NOTICE_BODY", null, "□ 신청자격", null, "중소기업");
        var recovered = new RequirementSource("NOTICE_BODY", null, "□ 사업 신청 자격", null,
                "□ 사업 신청 자격\n대학 또는 연구기관");
        assertThat(ParticipationSourceSelection.compatibleCandidate(recovered)).isFalse();
        assertThat(ParticipationSourceSelection.explicit(recovered)).isTrue();
        var baseline = ParticipationRewriteProjection.input(raw(old), false);
        var input = ParticipationRewriteProjection.input(raw(recovered, old), false);
        assertThat(input.request()).isEqualTo(baseline.request());
        var outbound = ParticipationSourceSelection.select(input).outbound(input);
        assertThat(outbound.sources()).hasSize(2).anySatisfy(source -> {
            assertThat(source.id()).startsWith("u");
            assertThat(source.excerpt()).isEqualTo(recovered.excerpt());
        });
        assertThat(input.evidence().get("s0")).containsExactly(old);
    }

    @Test void fundingOnlyPreferredFormatCannotHideAlternateApplicantEligibility() {
        var funding = new RequirementSource("ATTACHMENT", "공고.hwpx", "□ 지원조건", null,
                "□ 지원조건\n총사업비의 30~100% 이내 정부 지원");
        var applicant = new RequirementSource("ATTACHMENT", "공고.pdf", "□ 신청자격", null,
                "□ 신청자격\n대학 또는 연구기관");
        var input = ParticipationRewriteProjection.input(raw(funding, applicant), false);
        assertThat(input.request().sources()).isEmpty();
        assertThat(input.availableSources()).hasSize(2);
        var selected = ParticipationSourceSelection.select(input);
        assertThat(selected.includes(funding)).isFalse();
        assertThat(selected.includes(applicant)).isTrue();
        assertThat(selected.outbound(input).sources()).singleElement()
                .satisfies(item -> assertThat(item.excerpt()).isEqualTo(applicant.excerpt()));
    }
}
