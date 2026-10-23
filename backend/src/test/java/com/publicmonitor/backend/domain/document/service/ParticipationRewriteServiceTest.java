package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ParticipationRewriteServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final String quote = "업력 7년 이내 창업기업만 신청 가능";
    ParticipationGuide raw() {
        var source = new RequirementSource("NOTICE_BODY", null, "신청자격", null, quote);
        var rule = new ParticipationRule("BASIC", "대표자 조건", "잘못 붙인 문장입니다.", null, null,
                List.of("잘못된 세부 조건"), List.of(), List.of(), List.of(), List.of(source));
        return new ParticipationGuide(List.of(), List.of(rule), List.of(), List.of());
    }
    DocumentVersion version() {
        var version = DocumentVersion.create(null, 1, "공고", quote, "a".repeat(64), null, 0, null);
        ReflectionTestUtils.setField(version, "id", 1L);
        return version;
    }
    Response valid() {
        return new Response(List.of(new Statement(List.of("s0"), "BUSINESS_AGE", quote,
                "", List.of(quote), "ELIGIBILITY")), List.of(), "READY");
    }
    List<ParticipationCriterionEntry> entries(ParticipationGuide guide) {
        return guide.categories().stream().flatMap(c -> c.rows().stream()).flatMap(r -> r.entries().stream()).toList();
    }

    @Test void pendingUsesEvidenceOnlyAndSingleFlightThenReusesSavedResult() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var saved = new CountDownLatch(1);
        when(client.rewrite(any(), any())).thenAnswer(inv -> { entered.countDown(); release.await(3, TimeUnit.SECONDS); return valid(); });
        doAnswer(inv -> { saved.countDown(); return null; }).when(store).finish(any(), anyString(), anyString());
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = version();
            var pending = service.resolve(version, raw(), false);
            assertThat(pending.rewriteStatus()).isEqualTo("PENDING");
            assertThat(entries(pending)).allSatisfy(entry -> {
                assertThat(entry.displayStatus()).isEqualTo("EVIDENCE_ONLY");
                assertThat(entry.rule().text()).isEmpty();
                assertThat(entry.rule().details()).isEmpty();
                assertThat(entry.rule().sources().getFirst().excerpt()).isEqualTo(quote);
            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            service.resolve(version, raw(), false);
            verify(client, times(1)).rewrite(any(), any());
            release.countDown();
            assertThat(saved.await(3, TimeUnit.SECONDS)).isTrue();
            var input = ParticipationRewriteProjection.input(raw(), false);
            ReflectionTestUtils.setField(version, "participationRewriteKey", service.fingerprint(input.request()));
            ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(valid()));
            var ready = service.resolve(version, raw(), false);
            assertThat(ready.rewriteStatus()).isEqualTo("READY");
            assertThat(entries(ready)).singleElement().satisfies(entry -> {
                assertThat(entry.displayStatus()).isEqualTo("VERIFIED");
                assertThat(entry.rule().label()).isEqualTo("업력");
                assertThat(entry.rule().text()).isEqualTo(valid().statements().getFirst().text());
            });
            verify(client, times(1)).rewrite(any(), any());
        } finally { release.countDown(); service.close(); }
    }

    @Test void failedOrUnsupportedResultsRetainOriginalWithoutFabricatedFallback() {
        var input = ParticipationRewriteProjection.input(raw(), false);
        var failed = ParticipationRewriteProjection.guide(raw(), input, Response.unavailable(input.request()), "UNAVAILABLE");
        assertThat(entries(failed)).allMatch(entry -> entry.rule().text().isEmpty());
        assertThat(failed.categories().stream().flatMap(c -> c.rows().stream())).noneMatch(row -> row.status().equals("NOT_STATED"));
        var fabricated = new Response(List.of(new Statement(List.of("unknown"), "COMPANY_SIZE", "창작입니다.", "", List.of("창작 근거"), "ELIGIBILITY")), List.of(), "READY");
        var guide = ParticipationRewriteProjection.guide(raw(), input, fabricated, "READY");
        assertThat(entries(guide)).allMatch(entry -> entry.displayStatus().equals("EVIDENCE_ONLY"));
    }

    @Test void unresolvedSectionsCannotMakeUnclassifiedRowsLookAbsent() {
        var input = ParticipationRewriteProjection.input(raw(), false);
        var partial = new Response(valid().statements(), List.of("s0"), "READY");
        var guide = ParticipationRewriteProjection.guide(raw(), input, partial, "READY");
        assertThat(guide.categories().stream().flatMap(c -> c.rows().stream()))
                .noneMatch(row -> row.status().equals("NOT_STATED"));
        var complete = ParticipationRewriteProjection.guide(raw(), input, valid(), "READY");
        assertThat(complete.categories().stream().flatMap(c -> c.rows().stream()))
                .noneMatch(row -> row.status().equals("NOT_STATED"));
    }

    @Test void requestContainsRawEvidenceCatalogAndSnakeCaseContract() {
        var input = ParticipationRewriteProjection.input(raw(), false);
        assertThat(input.request().criteria()).extracting(Criterion::code)
                .containsExactly("BUSINESS_AGE", "REVENUE", "IP", "SECTOR", "BUSINESS_FORM", "EXPERIENCE", "CREDIT");
        String json = mapper.writeValueAsString(input.request());
        assertThat(json).contains("group_label", "business_types", quote).doesNotContain("잘못 붙인", "잘못된 세부 조건");
        assertThat(mapper.readValue(mapper.writeValueAsString(valid()), Response.class)).isEqualTo(valid());
    }

    @Test void sourceChangesInvalidateCacheAndFullTrackHeadingSurvivesCandidateSelection() {
        String source = "신청자격\n스케일업 100억 트랙\n업력 7년 이상의 매출액 100억원 달성 중소기업이 대상입니다.\n스케일업 500억 트랙\n업력 7년 이상의 매출액 500억원 달성 중소기업이 대상입니다.";
        var candidates = ParticipationConditionResolver.candidates(source, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates, false);
        assertThat(input.request().sources()).anySatisfy(item -> assertThat(item.excerpt()).contains("스케일업 100억 트랙", "스케일업 500억 트랙"));
        var service = new ParticipationRewriteService(mock(PythonParticipationRewriteClient.class), mock(ParticipationRewriteStore.class), mapper);
        try {
            assertThat(service.fingerprint(input.request())).isNotEqualTo(service.fingerprint(ParticipationRewriteProjection.input(raw(), false).request()));
        } finally { service.close(); }
    }
    @Test void completeOriginalTablesReachTheModelWithoutOldExtractorFiltering() throws Exception {
        String body = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/participation-conditions/clean-structured.txt"));
        var input = ParticipationRewriteProjection.input(ParticipationConditionResolver.candidates(body, List.of(), null), false);
        String evidence = input.request().sources().stream().map(Source::excerpt).collect(java.util.stream.Collectors.joining("\n"));
        assertThat(evidence).contains("C10~C34", "고위험업종 산재보험 가입 코드와 일치 필요");
    }

    @Test void verifiedProgressAppearsWhileLaterConditionsArePending() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var emitted = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(client.rewrite(any(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<Response> progress = inv.getArgument(1);
            progress.accept(valid()); emitted.countDown();
            release.await(3, TimeUnit.SECONDS); return valid();
        });
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = version();
            service.resolve(version, raw(), false);
            assertThat(emitted.await(3, TimeUnit.SECONDS)).isTrue();
            var pending = service.resolve(version, raw(), false);
            assertThat(pending.rewriteStatus()).isEqualTo("PENDING");
            assertThat(entries(pending)).singleElement().satisfies(entry -> {
                assertThat(entry.displayStatus()).isEqualTo("VERIFIED");
                assertThat(entry.rule().text()).isEqualTo(valid().statements().getFirst().text());
            });
            verify(store, never()).finish(any(), any(), any());
        } finally { release.countDown(); service.close(); }
    }

    @Test void previousBroadReadyCacheGetsOneFocusedExtraction() throws Exception {
        for (String policy : List.of("participation-llm-v5", "participation-economy-v1")) {
            var client = mock(PythonParticipationRewriteClient.class);
            var store = mock(ParticipationRewriteStore.class);
            when(client.rewrite(any(), any())).thenReturn(valid());
            var service = new ParticipationRewriteService(client, store, mapper);
            try {
                var version = version();
                var request = ParticipationRewriteProjection.input(raw(), false).request();
                String previousKey = ReflectionTestUtils.invokeMethod(service, "fingerprint", request, policy);
                ReflectionTestUtils.setField(version, "participationRewriteKey", previousKey);
                ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(valid()));
                assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("PENDING");
                awaitTerminal(service, version);
                var guide = service.resolve(version, raw(), false);
                assertThat(guide.rewriteStatus()).isEqualTo("READY");
                assertThat(entries(guide)).singleElement().satisfies(entry ->
                        assertThat(entry.rule().text()).isEqualTo(quote));
                verify(client, times(1)).rewrite(any(), any());
                verify(store).finish(eq(1L), eq(service.fingerprint(request)), anyString());
            } finally { service.close(); }
        }
    }

    @Test void previousFailureGetsOneFocusedAttemptThenRemainsTerminalAcrossRestart() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        when(client.rewrite(any(), any())).thenAnswer(inv -> Response.unavailable(inv.getArgument(0)));
        var version = version();
        doAnswer(inv -> {
            ReflectionTestUtils.setField(version, "participationRewriteKey", inv.getArgument(1));
            ReflectionTestUtils.setField(version, "participationRewriteJson", inv.getArgument(2));
            return null;
        }).when(store).finish(any(), anyString(), anyString());
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var request = ParticipationRewriteProjection.input(raw(), false).request();
            String legacyKey = ReflectionTestUtils.invokeMethod(service, "fingerprint", request, "participation-llm-v5");
            ReflectionTestUtils.setField(version, "participationRewriteKey", legacyKey);
            ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(Response.unavailable(request)));
            assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("PENDING");
            awaitTerminal(service, version);
            assertThat(version.getParticipationRewriteKey()).isEqualTo(service.fingerprint(request));
            for (int poll = 0; poll < 3; poll++) {
                assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("UNAVAILABLE");
            }
        } finally { service.close(); }
        var restarted = new ParticipationRewriteService(client, store, mapper);
        try {
            assertThat(restarted.resolve(version, raw(), false).rewriteStatus()).isEqualTo("UNAVAILABLE");
            verify(client, times(1)).rewrite(any(), any());
        } finally { restarted.close(); }
    }
    @Test void persistedFailureDoesNotRetryOnRepeatedLookupOrAfterRestart() {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var version = version();
        var request = ParticipationRewriteProjection.input(raw(), false).request();
        for (int restart = 0; restart < 2; restart++) {
            var service = new ParticipationRewriteService(client, store, mapper);
            try {
                ReflectionTestUtils.setField(version, "participationRewriteKey", service.fingerprint(request));
                ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(Response.unavailable(request)));
                for (int poll = 0; poll < 3; poll++) {
                    assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("UNAVAILABLE");
                }
            } finally { service.close(); }
        }
        verifyNoInteractions(client, store);
    }

    @Test void clientExceptionIsPersistedAndCannotRestartPaidWork() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        when(client.rewrite(any(), any())).thenThrow(new IllegalStateException("provider failed"));
        var version = version();
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("PENDING");
            awaitTerminal(service, version);
            var json = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(store).finish(eq(1L), anyString(), json.capture());
            assertThat(mapper.readValue(json.getValue(), Response.class).status()).isEqualTo("UNAVAILABLE");
            for (int poll = 0; poll < 3; poll++) {
                assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("UNAVAILABLE");
            }
            verify(client, times(1)).rewrite(any(), any());
        } finally { service.close(); }
    }

    @Test void storageFailureStillSuppressesRepeatedPaidWorkInMemory() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        when(client.rewrite(any(), any())).thenAnswer(inv -> Response.unavailable(inv.getArgument(0)));
        doThrow(new IllegalStateException("storage unavailable")).when(store).finish(any(), anyString(), anyString());
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = version();
            service.resolve(version, raw(), false);
            awaitTerminal(service, version);
            for (int poll = 0; poll < 3; poll++) {
                assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("UNAVAILABLE");
            }
            verify(client, times(1)).rewrite(any(), any());
            verify(store, times(1)).finish(any(), anyString(), anyString());
        } finally { service.close(); }
    }

    @Test void fullTerminalCacheStopsNewWorkButStillReadsPersistedResults() {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            @SuppressWarnings("unchecked")
            var completed = (java.util.Map<String, Response>) ReflectionTestUtils.getField(service, "completed");
            var request = ParticipationRewriteProjection.input(raw(), false).request();
            for (int index = 0; index < 256; index++) completed.put("cached-" + index, Response.unavailable(request));
            var version = version();
            assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("UNAVAILABLE");
            assertThat(completed).hasSize(256);
            ReflectionTestUtils.setField(version, "participationRewriteKey", service.fingerprint(request));
            ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(valid()));
            assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("READY");
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }
    private void awaitTerminal(ParticipationRewriteService service, DocumentVersion version) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (!"PENDING".equals(service.resolve(version, raw(), false).rewriteStatus())) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Participation rewrite did not finish within three seconds");
    }
    @Test void identicalParagraphsAreRewrittenOnceWithoutLosingEitherAttachment() {
        var a = new RequirementSource("ATTACHMENT", "notice.hwp", "신청자격", null, quote);
        var b = new RequirementSource("ATTACHMENT", "notice.pdf", "신청자격", null, quote.replace(" ", "  "));
        var rule = new ParticipationRule("EVIDENCE", "원문 조건", "", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(a, b));
        var raw = new ParticipationGuide(List.of(), List.of(rule), List.of(), List.of());
        var input = ParticipationRewriteProjection.input(raw, false);
        assertThat(input.request().sources()).hasSize(1);
        var ready = ParticipationRewriteProjection.guide(raw, input, valid(), "READY");
        assertThat(entries(ready)).singleElement().satisfies(entry ->
                assertThat(entry.rule().sources()).containsExactly(a, b));
    }

    @Test void fourFieldPaidResultsReuseTheirOriginalIdsWithoutRebillingAfterExpansion() throws Exception {
        for (String status : List.of("READY", "UNAVAILABLE")) {
            var client = mock(PythonParticipationRewriteClient.class);
            var store = mock(ParticipationRewriteStore.class);
            var data = raw();
            var extra = new ParticipationRule("EVIDENCE", "원문", "", null, null, List.of(), List.of(), List.of(), List.of(),
                    List.of(new RequirementSource("NOTICE_BODY", null, "지원대상", null, "전년도 직접수출액 5,000만불 미만")));
            var expandedRaw = new ParticipationGuide(List.of(), List.of(extra, data.rules().getFirst()), List.of(), List.of());
            var version = version();
            {
                var service = new ParticipationRewriteService(client, store, mapper);
                try {
                    var legacyInput = ParticipationRewriteProjection.legacyInput(expandedRaw, false, null);
                    String key = ReflectionTestUtils.invokeMethod(service, "fingerprint", legacyInput.request(), "participation-four-fields-v1");
                    var response = status.equals("READY") ? valid() : Response.unavailable(legacyInput.request());
                    ReflectionTestUtils.setField(version, "participationRewriteKey", key);
                    ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(response));
                    for (int attempt = 0; attempt < 2; attempt++) {
                        var guide = service.resolve(version, expandedRaw, false);
                        assertThat(guide.rewriteStatus()).isEqualTo(status);
                        if (status.equals("READY")) assertThat(guide.rules()).singleElement().satisfies(r -> assertThat(r.text()).isEqualTo(quote));
                    }
                    verifyNoInteractions(client, store);
                } finally { service.close(); }
            }
        }
    }

    @Test void previousSevenFieldPaidResultsAreReusedWithoutAnyNewCall() throws Exception {
        for (String status : List.of("READY", "UNAVAILABLE", "EMPTY")) {
            var client = mock(PythonParticipationRewriteClient.class);
            var store = mock(ParticipationRewriteStore.class);
            var service = new ParticipationRewriteService(client, store, mapper);
            try {
                String body = "1. 성장형\n□ 신청자격\n" + quote + "\n2. 시장형\n□ 지원대상\n전통시장 및 상점가";
                var candidates = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
                var frozen = candidates.scopes().compatibility();
                var oldRaw = new ParticipationGuide(List.of(), frozen.rules(), List.of(), List.of());
                var old = ParticipationRewriteProjection.previousInput(oldRaw, false, frozen.scopes());
                var current = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
                assertThat(current.request()).isNotEqualTo(old.request());
                var response = status.equals("UNAVAILABLE") ? Response.unavailable(old.request())
                        : new Response(List.of(), List.of(), "READY");
                if (status.equals("READY")) {
                    String id = old.request().sources().stream().filter(src -> src.excerpt().contains(quote)).findFirst().orElseThrow().id();
                    response = new Response(List.of(new Statement(List.of(id), "BUSINESS_AGE", quote, "", List.of(quote), "ELIGIBILITY")), List.of(), "READY");
                }
                var version = version();
                String key = ReflectionTestUtils.invokeMethod(service, "fingerprint", old.request(), "participation-seven-fields-v1");
                ReflectionTestUtils.setField(version, "participationRewriteKey", key);
                ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(response));
                var result = service.resolve(version, candidates.guide(), false, candidates.scopes());
                assertThat(result.rewriteStatus()).isEqualTo(response.status());
                assertThat(result.rules()).anyMatch(rule -> rule.text().equals("전통시장 및 상점가가 지원 대상입니다."));
                if (status.equals("READY")) assertThat(result.rules()).anyMatch(rule -> rule.text().equals(quote));
                else assertThat(result.rules()).noneMatch(rule -> rule.text().equals(quote));
                verifyNoInteractions(client, store);
            } finally { service.close(); }
        }
    }

    @Test void terminalEmptyCacheGetsLocalTargetSentencesWithoutPayingAgain() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var c = ParticipationConditionResolver.candidatesWithScopes("1. 성장형\n□ 지원대상\n매출액 20억원 이상인 중소기업", List.of(), null);
            var request = ParticipationRewriteProjection.input(c.guide(), false, c.scopes()).request();
            var version = version();
            ReflectionTestUtils.setField(version, "participationRewriteKey", service.fingerprint(request));
            for (String status : List.of("READY", "UNAVAILABLE")) {
                ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(new Response(List.of(), List.of(), status)));
                var result = service.resolve(version, c.guide(), false, c.scopes());
                assertThat(result.rules()).singleElement().satisfies(rule -> assertThat(rule.text()).contains("20억원 이상"));
            }
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void oldTerminalCacheKeepsOldSourceIdsAndUsesRecoveredLocalEvidenceWithoutPayment() {
        for (String status : List.of("READY", "UNAVAILABLE")) {
            var client = mock(PythonParticipationRewriteClient.class);
            var store = mock(ParticipationRewriteStore.class);
            var service = new ParticipationRewriteService(client, store, mapper);
            try {
                String body = "□ 신청자격\n업력 7년 이내 창업기업만 신청 가능\n[표]\n양식교부 및 접수처\t홈페이지\n[/표]\n"
                        + "4-2. 신청자격\n□ 지원대상 : 중견기업";
                var candidates = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
                var frozen = candidates.scopes().compatibility();
                var oldRaw = new ParticipationGuide(List.of(), frozen.rules(), List.of(), List.of());
                var oldInput = ParticipationRewriteProjection.input(oldRaw, false, frozen.scopes());
                String oldKey = ReflectionTestUtils.invokeMethod(service, "fingerprint", oldInput.request(), "participation-seven-fields-v2");
                var version = version();
                ReflectionTestUtils.setField(version, "participationRewriteKey", oldKey);
                var response = new Response(status.equals("READY") ? valid().statements() : List.of(), List.of(), status);
                ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(response));
                var guide = service.resolve(version, candidates.guide(), false, candidates.scopes());
                assertThat(guide.rewriteStatus()).isEqualTo(status);
                assertThat(guide.rules()).anyMatch(rule -> rule.text().equals("중견기업이 지원 대상입니다."));
                if (status.equals("READY")) assertThat(guide.rules()).anyMatch(rule -> rule.text().equals(quote));
                verifyNoInteractions(client, store);
            } finally { service.close(); }
        }
    }
}
