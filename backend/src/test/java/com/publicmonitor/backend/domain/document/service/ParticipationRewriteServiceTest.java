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
    final String sentence = quote + "합니다.";
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

    @Test void pendingKeepsReadableOriginalAndSingleFlightThenReusesSavedResult() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var saved = new CountDownLatch(1);
        when(client.rewrite(any(), any())).thenAnswer(inv -> { entered.countDown(); release.await(3, TimeUnit.SECONDS); return valid(); });
        doAnswer(inv -> { saved.countDown(); return null; }).when(store).finish(any(), anyString(), anyString());
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = version();
            var pending = service.resolve(version, raw(), false);
            assertThat(pending.rewriteStatus()).isEqualTo("PENDING");
            assertThat(pending.rules()).singleElement().satisfies(rule -> assertThat(rule.text()).isEqualTo(sentence));
            assertThat(entries(pending).stream().filter(entry -> entry.displayStatus().equals("EVIDENCE_ONLY"))).allSatisfy(entry -> {
                assertThat(entry.rule().text()).isEmpty();
                assertThat(entry.rule().details()).isEmpty();
                assertThat(entry.rule().sources().getFirst().excerpt()).isEqualTo(quote);
            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            service.resolve(version, raw(), false);
            verify(client, times(1)).rewrite(any(), any());
            release.countDown();
            assertThat(saved.await(3, TimeUnit.SECONDS)).isTrue();
            awaitTerminal(service, version);
            var input = ParticipationRewriteProjection.input(raw(), false);
            ReflectionTestUtils.setField(version, "participationRewriteKey", service.fingerprint(input.request()));
            ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(valid()));
            var ready = service.resolve(version, raw(), false);
            assertThat(ready.rewriteStatus()).isEqualTo("READY");
            assertThat(entries(ready)).singleElement().satisfies(entry -> {
                assertThat(entry.displayStatus()).isEqualTo("VERIFIED");
                assertThat(entry.rule().label()).isEqualTo("업력");
                assertThat(entry.rule().text()).isEqualTo(sentence);
            });
            verify(client, times(1)).rewrite(any(), any());
        } finally { release.countDown(); service.close(); }
    }

    @Test void failedOrUnsupportedResultsRecoverOnlyReadableOriginalWithoutUsingFabricatedText() {
        var input = ParticipationRewriteProjection.input(raw(), false);
        var failed = ParticipationRewriteProjection.guide(raw(), input, Response.unavailable(input.request()), "UNAVAILABLE");
        assertThat(failed.rules()).singleElement().satisfies(rule -> assertThat(rule.text()).isEqualTo(sentence));
        assertThat(failed.categories().stream().flatMap(c -> c.rows().stream())).noneMatch(row -> row.status().equals("NOT_STATED"));
        var fabricated = new Response(List.of(new Statement(List.of("unknown"), "COMPANY_SIZE", "창작입니다.", "", List.of("창작 근거"), "ELIGIBILITY")), List.of(), "READY");
        var guide = ParticipationRewriteProjection.guide(raw(), input, fabricated, "READY");
        assertThat(guide.rules()).singleElement().satisfies(rule -> assertThat(rule.text()).isEqualTo(sentence).doesNotContain("창작"));
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
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
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
                assertThat(entry.rule().text()).isEqualTo(sentence);
            });
            verify(store, never()).finish(any(), any(), any());
        } finally { release.countDown(); service.close(); }
    }

    @Test void unmatchedOldCacheRefreshesOnceAndReusesPersistedSuccessOrFailureAfterRestart() throws Exception {
        for (String oldStatus : List.of("READY", "UNAVAILABLE")) for (String newStatus : List.of("READY", "UNAVAILABLE")) {
            var client = mock(PythonParticipationRewriteClient.class);
            var store = mock(ParticipationRewriteStore.class);
            when(store.claim(any(), anyString(), eq("stale-input"), anyString())).thenReturn(true);
            var request = ParticipationRewriteProjection.input(raw(), false).request();
            var result = newStatus.equals("READY") ? valid() : Response.unavailable(request);
            when(client.rewrite(any(), any())).thenReturn(result);
            var saved = new CountDownLatch(1);
            var json = new java.util.concurrent.atomic.AtomicReference<String>();
            doAnswer(inv -> { json.set(inv.getArgument(2)); saved.countDown(); return null; })
                    .when(store).finish(any(), anyString(), anyString());
            var version = version();
            ReflectionTestUtils.setField(version, "participationRewriteKey", "stale-input");
            ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(new Response(valid().statements(), List.of(), oldStatus)));
            var service = new ParticipationRewriteService(client, store, mapper);
            String key = service.fingerprint(request);
            try {
                assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("PENDING");
                assertThat(saved.await(3, TimeUnit.SECONDS)).isTrue();
                awaitTerminal(service, version);
                for (int poll=0; poll<3; poll++) assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo(newStatus);
            } finally { service.close(); }
            ReflectionTestUtils.setField(version, "participationRewriteKey", key);
            ReflectionTestUtils.setField(version, "participationRewriteJson", json.get());
            for (int restart=0; restart<2; restart++) {
                var restarted = new ParticipationRewriteService(client, store, mapper);
                try {
                    for (int poll=0; poll<3; poll++) assertThat(restarted.resolve(version, raw(), false).rewriteStatus()).isEqualTo(newStatus);
                } finally { restarted.close(); }
            }
            verify(client, times(1)).rewrite(any(), any());
            verify(store, times(1)).claim(eq(1L), eq(key), eq("stale-input"), anyString());
            verify(store, times(1)).finish(eq(1L), eq(key), anyString());
        }
    }

    @Test void persistedFailureDoesNotRetryOnRepeatedLookupOrAfterRestart() {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
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
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
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
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
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
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
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
    @Test void persistedPendingSurvivesRestartAndExpiresWithoutAnotherCall() {
        for (long started : List.of(System.currentTimeMillis(), System.currentTimeMillis() - 121_000)) {
            var client = mock(PythonParticipationRewriteClient.class);
            var store = mock(ParticipationRewriteStore.class);
            var version = version();
            for (int restart=0; restart<2; restart++) {
                var service = new ParticipationRewriteService(client, store, mapper);
                try {
                    String key = service.fingerprint(ParticipationRewriteProjection.input(raw(), false).request());
                    ReflectionTestUtils.setField(version, "participationRewriteKey", key);
                    ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(new ParticipationRewriteService.Pending("PENDING", started)));
                    String expected = System.currentTimeMillis() - started < 120_000 ? "PENDING" : "UNAVAILABLE";
                    for (int poll=0; poll<3; poll++) assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo(expected);
                } finally { service.close(); }
            }
            verifyNoInteractions(client, store);
        }
    }

    @Test void corruptOrInterruptedCacheForCurrentInputCannotChargeAgain() {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = version();
            ReflectionTestUtils.setField(version, "participationRewriteKey", service.fingerprint(ParticipationRewriteProjection.input(raw(), false).request()));
            for (String json : java.util.Arrays.asList(null, "invalid", "null", "{}", "{\"status\":\"PENDING\"}")) {
                ReflectionTestUtils.setField(version, "participationRewriteJson", json);
                assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("UNAVAILABLE");
            }
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void simultaneousServiceInstancesClaimTheInputOnceAndLoserReadsPersistedResult() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var claimed = new java.util.concurrent.atomic.AtomicBoolean();
        var attempts = new CountDownLatch(2); var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1); var saved = new CountDownLatch(1);
        var storedJson = new java.util.concurrent.atomic.AtomicReference<String>();
        when(store.claim(any(), anyString(), isNull(), anyString())).thenAnswer(inv -> {
            boolean won = claimed.compareAndSet(false, true);
            if (won) storedJson.set(inv.getArgument(3));
            attempts.countDown(); return won;
        });
        when(client.rewrite(any(), any())).thenAnswer(inv -> { entered.countDown(); release.await(3, TimeUnit.SECONDS); return valid(); });
        doAnswer(inv -> { storedJson.set(inv.getArgument(2)); saved.countDown(); return null; }).when(store).finish(any(), anyString(), anyString());
        var a = new ParticipationRewriteService(client, store, mapper);
        var b = new ParticipationRewriteService(client, store, mapper);
        try {
            a.resolve(version(), raw(), false); b.resolve(version(), raw(), false);
            assertThat(attempts.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(mapper.readTree(storedJson.get()).path("status").asText()).isEqualTo("PENDING");
            release.countDown(); assertThat(saved.await(3, TimeUnit.SECONDS)).isTrue();
            var persisted = version();
            ReflectionTestUtils.setField(persisted, "participationRewriteKey", a.fingerprint(ParticipationRewriteProjection.input(raw(), false).request()));
            ReflectionTestUtils.setField(persisted, "participationRewriteJson", storedJson.get());
            for (var service : List.of(a,b)) {
                awaitTerminal(service, persisted);
                assertThat(service.resolve(persisted, raw(), false).rewriteStatus()).isEqualTo("READY");
            }
            verify(client, times(1)).rewrite(any(), any());
            verify(store, times(1)).finish(any(), anyString(), anyString());
        } finally { release.countDown(); a.close(); b.close(); }
    }

    @Test void databaseClaimFailureDoesNotInvokeModelOrOverwriteCache() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        when(store.claim(any(), anyString(), any(), anyString())).thenThrow(new IllegalStateException("db unavailable"));
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = version(); service.resolve(version, raw(), false); awaitTerminal(service, version);
            assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("UNAVAILABLE");
            verifyNoInteractions(client); verify(store, never()).finish(any(), anyString(), anyString());
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

    @Test void olderPoliciesRefreshOnceAndReuseTheNewTerminalResultAfterRestart() throws Exception {
        for (String policy : List.of("participation-four-fields-v1", "participation-seven-fields-v1",
                "participation-seven-fields-v2", "participation-seven-fields-v3", "participation-applicant-conditions-v4")) {
            for (String status : List.of("READY", "UNAVAILABLE")) {
                var client = mock(PythonParticipationRewriteClient.class);
                var store = mock(ParticipationRewriteStore.class);
                var service = new ParticipationRewriteService(client, store, mapper);
                var version = version();
                var request = ParticipationRewriteProjection.input(raw(), false).request();
                String oldKey = ReflectionTestUtils.invokeMethod(service, "fingerprint", request, policy);
                ReflectionTestUtils.setField(version, "participationRewriteKey", oldKey);
                ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(valid()));
                var result = status.equals("READY") ? valid() : Response.unavailable(request);
                var saved = new CountDownLatch(1);
                when(store.claim(eq(1L), anyString(), eq(oldKey), anyString())).thenReturn(true);
                when(client.rewrite(any(), any())).thenReturn(result);
                doAnswer(invocation -> {
                    ReflectionTestUtils.setField(version, "participationRewriteKey", invocation.getArgument(1));
                    ReflectionTestUtils.setField(version, "participationRewriteJson", invocation.getArgument(2));
                    saved.countDown(); return null;
                }).when(store).finish(eq(1L), anyString(), anyString());
                try {
                    assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("PENDING");
                    assertThat(saved.await(3, TimeUnit.SECONDS)).isTrue();
                    assertThat(version.getParticipationRewriteKey()).isEqualTo(service.fingerprint(request));
                    assertThat(version.getParticipationRewriteKey()).isNotEqualTo(oldKey);
                    verify(client, times(1)).rewrite(any(), any());
                    verify(store, times(1)).claim(eq(1L), anyString(), eq(oldKey), anyString());
                } finally { service.close(); }
                var restartedClient = mock(PythonParticipationRewriteClient.class);
                var restartedStore = mock(ParticipationRewriteStore.class);
                var restarted = new ParticipationRewriteService(restartedClient, restartedStore, mapper);
                try {
                    for (int read = 0; read < 3; read++)
                        assertThat(restarted.resolve(version, raw(), false).rewriteStatus()).isEqualTo(status);
                    verifyNoInteractions(restartedClient, restartedStore);
                } finally { restarted.close(); }
            }
        }
    }

    @Test void terminalEmptyCacheShowsOnlyOriginalEvidenceWithoutPayingAgain() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var c = ParticipationConditionResolver.candidatesWithScopes("1. 성장형\n□ 지원대상\n매출액 20억원 이상인 중소기업", List.of(), null);
            var request = ParticipationRewriteProjection.input(c.guide(), false, c.scopes()).request();
            var version = version();
            ReflectionTestUtils.setField(version, "participationRewriteKey", service.fingerprint(request));
            for (String status : List.of("READY", "UNAVAILABLE")) {
                ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(new Response(List.of(), List.of(), status)));
                var result = service.resolve(version, c.guide(), false, c.scopes());
                assertThat(result.rules()).isEmpty();
                assertThat(entries(result)).isNotEmpty().allMatch(entry -> entry.displayStatus().equals("EVIDENCE_ONLY"));
                assertThat(entries(result)).flatExtracting(entry -> entry.rule().sources()).anyMatch(source -> source.excerpt().contains("20억원 이상"));
            }
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void savedEmptyQualificationCacheRecoversDisplayAcrossRestartWithoutChangingStoredResultOrCallingAi() {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
        for (String status : List.of("READY", "UNAVAILABLE")) {
            var version = version();
            String json = mapper.writeValueAsString(new Response(List.of(), List.of(), status));
            String key = null;
            for (int restart = 0; restart < 2; restart++) {
                var service = new ParticipationRewriteService(client, store, mapper);
                try {
                    if (key == null) key = service.fingerprint(ParticipationRewriteProjection.input(raw(), false).request());
                    ReflectionTestUtils.setField(version, "participationRewriteKey", key);
                    ReflectionTestUtils.setField(version, "participationRewriteJson", json);
                    for (int lookup = 0; lookup < 2; lookup++) {
                        var guide = service.resolve(version, raw(), false);
                        assertThat(guide.rewriteStatus()).isEqualTo(status);
                        assertThat(guide.rules()).singleElement().satisfies(rule -> {
                            assertThat(rule.text()).isEqualTo(sentence);
                            assertThat(rule.sources()).singleElement().satisfies(source -> assertThat(source.excerpt()).isEqualTo(quote));
                        });
                        assertThat(version.getParticipationRewriteKey()).isEqualTo(key);
                        assertThat(version.getParticipationRewriteJson()).isEqualTo(json);
                    }
                } finally { service.close(); }
            }
        }
        verifyNoInteractions(client, store);
    }

    @Test void historicalProjectionKeepsSourceIdsWithoutInterpretingTablesOrSupportTargets() {
        for (String status : List.of("READY", "UNAVAILABLE")) {
            var client = mock(PythonParticipationRewriteClient.class);
            var store = mock(ParticipationRewriteStore.class);
            when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
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
                var guide = ParticipationRewriteProjection.guide(candidates.guide(), oldInput, response, status,
                        ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes()));
                assertThat(guide.rewriteStatus()).isEqualTo(status);
                assertThat(guide.rules()).noneMatch(rule -> rule.text().contains("중견기업"));
                assertThat(entries(guide)).flatExtracting(entry -> entry.rule().sources())
                        .anyMatch(source -> source.excerpt().contains("중견기업"));
                if (status.equals("READY")) assertThat(guide.rules()).anyMatch(rule -> rule.text().equals(sentence));
                verifyNoInteractions(client, store);
            } finally { service.close(); }
        }
    }
}
