package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ParticipationSourceSelectionTest {
    private ParticipationGuide raw(String... names) {
        var rules = new ArrayList<ParticipationRule>();
        for (int index = 0; index < names.length; index++) {
            var source = new RequirementSource(names[index] == null ? "NOTICE_BODY" : "ATTACHMENT", names[index],
                    "[상위 구역] 실제 사업\n[별도 참여 역할] 구매기관\n[적용 유형] 신청자격 및 신청요건\n[원문 구역] □ 신청자격", null, "중소기업 업력 " + (index + 1) + "년 이상");
            rules.add(new ParticipationRule("EVIDENCE", "원문", "", null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(source)));
        }
        return new ParticipationGuide(List.of(), rules, List.of(), List.of());
    }
    private List<RequirementSource> evidence(ParticipationGuide guide) {
        return guide.categories().stream().flatMap(c -> c.rows().stream()).flatMap(row -> row.entries().stream())
                .flatMap(entry -> entry.rule().sources().stream()).distinct().toList();
    }

    private ParticipationGuide sources(RequirementSource... sources) {
        var rules = Arrays.stream(sources).map(source -> new ParticipationRule("EVIDENCE", "원문", "", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(source))).toList();
        return new ParticipationGuide(List.of(), rules, List.of(), List.of());
    }

    @Test void explicitEligibilityOutsideOldVocabularyIsSentWithoutChangingFrozenIdsOrFingerprint() {
        var unknown = new RequirementSource("ATTACHMENT", "공고.pdf", "□ 신청자격", null, "대학 또는 연구기관");
        var known = new RequirementSource("NOTICE_BODY", null, "□ 신청자격", null, "중소기업");
        var data = sources(unknown, known);
        var input = ParticipationRewriteProjection.input(data, false);
        assertThat(input.request().sources()).singleElement().satisfies(source -> {
            assertThat(source.id()).isEqualTo("s0");
            assertThat(source.excerpt()).isEqualTo("중소기업");
        });
        var outbound = ParticipationSourceSelection.select(input).outbound(input);
        assertThat(outbound.sources()).hasSize(2);
        var supplemental = outbound.sources().stream().filter(source -> source.id().startsWith("u")).findFirst().orElseThrow();
        assertThat(supplemental.excerpt()).isEqualTo("대학 또는 연구기관");
        var reordered = ParticipationRewriteProjection.input(sources(known, unknown), false);
        assertThat(reordered.request()).isEqualTo(input.request());
        assertThat(reordered.availableSources()).anyMatch(source -> source.id().equals(supplemental.id())
                && source.excerpt().equals(supplemental.excerpt()));
        var response = new Response(List.of(new Statement(List.of(supplemental.id()), "BUSINESS_FORM",
                "대학 또는 연구기관이 신청할 수 있습니다.", "", List.of(supplemental.excerpt()), "ELIGIBILITY")),
                List.of("s0"), "READY");
        assertThat(ParticipationRewriteProjection.guide(data, input, response, "READY").rules()).extracting(ParticipationRule::text)
                .containsExactly(response.statements().getFirst().text(), "신청 대상은 중소기업입니다.");
    }

    @Test void repeatedSupplementalConditionRetainsEveryActualParentProgramWithoutChangingFrozenRequest() {
        for (String ending : List.of("", "\n")) {
            String body = "1. 성장형\n□ 신청자격\n대학 또는 연구기관\n2. 확장형\n□ 신청자격\n대학 또는 연구기관" + ending;
            var candidates = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
            var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
            assertThat(input.request().sources()).isEmpty();
            var outbound = ParticipationSourceSelection.select(input).outbound(input);
            assertThat(outbound.sources()).isNotEmpty().allMatch(source -> source.id().startsWith("u"));
            String headings = outbound.sources().stream().map(Source::groupLabel).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(headings).contains("성장형", "확장형", "[상위 구역]").doesNotContain("[적용 유형]", "[별도 참여 역할]");
            var oldIdentity = new Request(List.of(), input.request().criteria(), input.request().businessTypes());
            assertThat(input.request()).isEqualTo(oldIdentity);
        }
    }

    @Test void preferredFormatWithoutExplicitEligibilityCannotHideTheOtherFormatsEligibility() {
        var data = sources(new RequirementSource("ATTACHMENT", "공고.hwpx", "□ 지원내용", null, "중소기업 연구 지원"),
                new RequirementSource("ATTACHMENT", "공고.pdf", "□ 신청자격", null, "대학 또는 연구기관"));
        var input = ParticipationRewriteProjection.input(data, false);
        var outbound = ParticipationSourceSelection.select(input).outbound(input);
        assertThat(outbound.sources()).singleElement().satisfies(source -> assertThat(source.excerpt()).isEqualTo("대학 또는 연구기관"));
        assertThat(evidence(ParticipationRewriteProjection.guide(data, input, Response.unavailable(input.request()), "UNAVAILABLE")))
                .extracting(RequirementSource::attachmentName).containsExactly("공고.pdf");
    }

    @Test void overBudgetPreferredSectionFallsBackToAReadableAlternateExport() {
        var data = sources(new RequirementSource("ATTACHMENT", "공고.hwpx", "□ 신청자격", null, "중소기업 조건 ".repeat(1000)),
                new RequirementSource("ATTACHMENT", "공고.pdf", "□ 신청자격", null, "중소기업 업력 3년 이상"));
        var input = ParticipationRewriteProjection.input(data, false);
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources()).singleElement()
                .satisfies(source -> assertThat(source.id()).isEqualTo("s1"));
    }

    @Test void noExplicitEligibilityInAnyFormatPreservesAllOriginalEvidence() {
        var data = sources(new RequirementSource("ATTACHMENT", "공고.hwpx", "□ 지원내용", null, "중소기업 연구 지원"),
                new RequirementSource("ATTACHMENT", "공고.pdf", "□ 사업내용", null, "제조업 중소기업 성장 지원"));
        var input = ParticipationRewriteProjection.input(data, false);
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources()).hasSize(2);
        assertThat(evidence(ParticipationRewriteProjection.guide(data, input, Response.unavailable(input.request()), "UNAVAILABLE")))
                .extracting(RequirementSource::attachmentName).containsExactlyInAnyOrder("공고.hwpx", "공고.pdf");
    }

    @Test void newNoticeWithOnlySupplementalEligibilityRunsOnceButExistingEmptyCacheNeverRuns() throws Exception {
        var data = sources(new RequirementSource("NOTICE_BODY", null, "□ 신청자격", null, "대학 또는 연구기관"));
        var input = ParticipationRewriteProjection.input(data, false);
        assertThat(input.request().sources()).isEmpty();
        assertThat(input.availableSources()).hasSize(1);
        var mapper = new ObjectMapper();
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var saved = new CountDownLatch(1);
        when(client.rewrite(any(), any())).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            assertThat(request.sources()).singleElement().satisfies(source -> assertThat(source.id()).startsWith("u"));
            return new Response(List.of(), request.sources().stream().map(Source::id).toList(), "READY");
        });
        doAnswer(invocation -> { saved.countDown(); return null; }).when(store).finish(any(), anyString(), anyString());
        var service = new ParticipationRewriteService(client, store, mapper);
        String key;
        try {
            key = service.fingerprint(input.request());
            var fresh = DocumentVersion.create(null, 1, "공고", "대학 또는 연구기관", "a".repeat(64), null, 0, null);
            ReflectionTestUtils.setField(fresh, "id", 97L);
            assertThat(service.resolve(fresh, data, false).rewriteStatus()).isEqualTo("PENDING");
            assertThat(saved.await(3, TimeUnit.SECONDS)).isTrue();
            verify(client, times(1)).rewrite(any(), any());
            verify(store).begin(97L, key);
        } finally { service.close(); }
        var untouchedClient = mock(PythonParticipationRewriteClient.class);
        var untouchedStore = mock(ParticipationRewriteStore.class);
        var restarted = new ParticipationRewriteService(untouchedClient, untouchedStore, mapper);
        try {
            var previous = DocumentVersion.create(null, 1, "공고", "대학 또는 연구기관", "b".repeat(64), null, 0, null);
            ReflectionTestUtils.setField(previous, "id", 98L);
            ReflectionTestUtils.setField(previous, "participationRewriteKey", key);
            ReflectionTestUtils.setField(previous, "participationRewriteJson", mapper.writeValueAsString(new Response(List.of(), List.of(), "READY")));
            var result = restarted.resolve(previous, data, false);
            assertThat(result.rewriteStatus()).isEqualTo("READY");
            assertThat(result.rules()).singleElement().satisfies(rule -> assertThat(rule.text()).endsWith("다."));
            assertThat(evidence(result)).singleElement().satisfies(source -> assertThat(source.excerpt()).isEqualTo("대학 또는 연구기관"));
            verifyNoInteractions(untouchedClient, untouchedStore);
        } finally { restarted.close(); }
    }

    @Test void sameNameAlternateExportsUseOneReadableRepresentativeAndKeepBody() {
        var data = raw(null, "공고.pdf", "공고.HWPX", "별도공고.pdf");
        var input = ParticipationRewriteProjection.input(data, false);
        var outbound = ParticipationSourceSelection.select(input).outbound(input);
        assertThat(input.request().sources()).extracting(Source::id).containsExactly("s0", "s1", "s2", "s3");
        assertThat(outbound.sources()).extracting(Source::id).containsExactly("s0", "s2", "s3");
        assertThat(outbound.sources()).allSatisfy(source -> {
            assertThat(source.groupLabel()).doesNotContain("[적용 유형]", "[별도 참여 역할]").contains("[상위 구역] 실제 사업", "[원문 구역] □ 신청자격");
            assertThat(input.request().sources()).anyMatch(original -> original.id().equals(source.id())
                    && original.excerpt().equals(source.excerpt()) && original.criteria().equals(source.criteria()));
        });
        assertThat(input.request().sources()).allMatch(source -> source.groupLabel().contains("[적용 유형]"));
        var missing = ParticipationRewriteProjection.guide(data, input, Response.unavailable(input.request()), "UNAVAILABLE");
        assertThat(evidence(missing)).extracting(RequirementSource::attachmentName).containsExactly(null, "공고.HWPX", "별도공고.pdf");
    }

    @Test void alreadyMergedWhitespaceVariantsUseTheSelectedExportWithoutChangingTheirSharedId() {
        String pdf = "중소기업 업력  3년  이상", hwpx = "중소기업 업력\t3년 이상";
        var sources = List.of(new RequirementSource("ATTACHMENT", "공고.pdf", "신청자격", null, pdf),
                new RequirementSource("ATTACHMENT", "공고.hwpx", "신청자격", null, hwpx));
        var rule = new ParticipationRule("EVIDENCE", "원문", "", null, null,
                List.of(), List.of(), List.of(), List.of(), sources);
        var data = new ParticipationGuide(List.of(), List.of(rule), List.of(), List.of());
        var input = ParticipationRewriteProjection.input(data, false);
        assertThat(input.request().sources()).singleElement().satisfies(source -> assertThat(source.excerpt()).isEqualTo(pdf));
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources()).singleElement().satisfies(source -> {
            assertThat(source.id()).isEqualTo("s0");
            assertThat(source.excerpt()).isEqualTo(hwpx);
        });
        assertThat(input.request().sources().getFirst().excerpt()).isEqualTo(pdf);
        assertThat(input.evidence().get("s0")).isEqualTo(sources);
    }

    @Test void preferenceWorksWhenAnyEarlierFormatIsAbsent() {
        for (var names : List.of(List.of("공고.pdf", "공고.hwp"), List.of("공고.pdf", "공고.docx"),
                List.of("공고.pdf", "공고.xlsx"), List.of("공고.pdf"))) {
            var input = ParticipationRewriteProjection.input(raw(names.toArray(String[]::new)), false);
            var outbound = ParticipationSourceSelection.select(input).outbound(input);
            assertThat(outbound.sources()).singleElement().satisfies(source ->
                    assertThat(source.id()).isEqualTo("s" + (names.size() - 1)));
        }
    }

    @Test void exactStemAndDifferentFormatsAreRequiredForSelection() {
        var input = ParticipationRewriteProjection.input(raw("공고.pdf", "공고.pdf", "공고 (수정).hwpx", "공고 .hwp",
                "공고.zip", "NOTICE.hwp", "notice.pdf"), false);
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources()).hasSize(7);
    }

    @Test void storedStatementKeepsEveryQuoteEvenWhenAnAlternateFormatWouldBeExcludedFromNewInput() {
        var data = raw("공고.hwpx", "공고.pdf");
        var input = ParticipationRewriteProjection.input(data, false);
        var quotes = input.request().sources().stream().map(Source::excerpt).toList();
        String text = "성장형은 업력 1년 이상, 확장형은 업력 2년 이상이어야 합니다.";
        var response = new Response(List.of(new Statement(List.of("s0", "s1"), "BUSINESS_AGE", text, "", quotes,
                "ELIGIBILITY", null, null)), List.of(), "READY");
        var guide = ParticipationRewriteProjection.guide(data, input, response, "READY");
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.text()).isEqualTo(text);
            assertThat(rule.sources()).extracting(RequirementSource::attachmentName).containsExactly("공고.hwpx", "공고.pdf");
        });
        assertThat(guide.categories().getFirst().rows().getFirst().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.supportingQuotes()).isEqualTo(quotes));
    }

    @Test void outboundSelectionKeepsOriginalFingerprintAndReusesTheResultAcrossRestart() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var mapper = new ObjectMapper();
        var data = raw("공고.pdf", "공고.hwpx");
        var input = ParticipationRewriteProjection.input(data, false);
        var version = DocumentVersion.create(null, 1, "공고", "중소기업", "a".repeat(64), null, 0, null);
        ReflectionTestUtils.setField(version, "id", 91L);
        var saved = new CountDownLatch(1);
        when(client.rewrite(any(), any())).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            assertThat(request.sources()).extracting(Source::id).containsExactly("s1");
            String quote = request.sources().getFirst().excerpt();
            return new Response(List.of(new Statement(List.of("s1"), "BUSINESS_AGE", "업력 2년 이상이어야 합니다.", "",
                    List.of(quote), "ELIGIBILITY", "성장형", "참여기업")), List.of(), "READY");
        });
        doAnswer(invocation -> {
            ReflectionTestUtils.setField(version, "participationRewriteKey", invocation.getArgument(1));
            ReflectionTestUtils.setField(version, "participationRewriteJson", invocation.getArgument(2));
            saved.countDown();
            return null;
        }).when(store).finish(any(), anyString(), anyString());
        String key;
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            key = service.fingerprint(input.request());
            assertThat(service.resolve(version, data, false).rewriteStatus()).isEqualTo("PENDING");
            assertThat(saved.await(3, TimeUnit.SECONDS)).isTrue();
            verify(store).begin(91L, key);
            assertThat(version.getParticipationRewriteKey()).isEqualTo(key);
        } finally { service.close(); }
        var restarted = new ParticipationRewriteService(client, store, mapper);
        try {
            var result = restarted.resolve(version, data, false);
            assertThat(result.rewriteStatus()).isEqualTo("READY");
            assertThat(result.rules()).singleElement().satisfies(rule -> {
                assertThat(rule.scope()).isEqualTo("성장형");
                assertThat(rule.audience()).isEqualTo("참여기업");
                assertThat(rule.sources()).extracting(RequirementSource::attachmentName).containsExactly("공고.hwpx");
            });
            verify(client, times(1)).rewrite(any(), any());
            verify(store, times(1)).finish(eq(91L), eq(key), anyString());
        } finally { restarted.close(); }
    }

    @Test void malformedStoredCacheNeverTriggersPaidRepair() {
        for (String stored : List.of("{broken", "{\"status\":\"READY\"}", "{\"status\":\"PENDING\"}")) {
            var client = mock(PythonParticipationRewriteClient.class);
            var store = mock(ParticipationRewriteStore.class);
            var service = new ParticipationRewriteService(client, store, new ObjectMapper());
            try {
                var data = raw("공고.hwpx", "공고.pdf");
                var version = DocumentVersion.create(null, 1, "공고", "중소기업", "a".repeat(64), null, 0, null);
                ReflectionTestUtils.setField(version, "id", 92L);
                ReflectionTestUtils.setField(version, "participationRewriteKey", "old-cache");
                ReflectionTestUtils.setField(version, "participationRewriteJson", stored);
                var result = service.resolve(version, data, false);
                assertThat(result.rewriteStatus()).isNotEqualTo("PENDING");
                assertThat(result.rules()).singleElement().satisfies(rule -> assertThat(rule.text()).endsWith("다."));
                assertThat(evidence(result)).extracting(RequirementSource::attachmentName).containsExactly("공고.hwpx");
                verifyNoInteractions(client, store);
            } finally { service.close(); }
        }
    }
}
