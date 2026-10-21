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
    final String quote = "대표자 또는 기업이 보조금법 위반으로 정부 지원사업에 참여제한 중이면 신청 제한";
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
        return new Response(List.of(new Statement(List.of("s0"), "SANCTIONS",
                "보조금법 위반으로 정부 지원사업 참여가 제한된 대표자 또는 기업은 신청할 수 없습니다.",
                "", List.of(quote), "EXCLUSION")), List.of(), "READY");
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
                assertThat(entry.rule().label()).isEqualTo("정부지원사업 참여 제한 처분");
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
                .anyMatch(row -> row.status().equals("NOT_STATED"));
    }

    @Test void requestContainsRawEvidenceCatalogAndSnakeCaseContract() {
        var input = ParticipationRewriteProjection.input(raw(), false);
        assertThat(input.request().criteria()).hasSize(40);
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
        assertThat(evidence).contains("C10~C34", "고위험업종 산재보험 가입 코드와 일치 필요", "630만원", "270만원");
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

    @Test void persistedTemporaryFailureCanRetryAfterRestart() throws Exception {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var entered = new CountDownLatch(1);
        when(client.rewrite(any(), any())).thenAnswer(inv -> { entered.countDown(); return valid(); });
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = version();
            var request = ParticipationRewriteProjection.input(raw(), false).request();
            ReflectionTestUtils.setField(version, "participationRewriteKey", service.fingerprint(request));
            ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(Response.unavailable(request)));
            assertThat(service.resolve(version, raw(), false).rewriteStatus()).isEqualTo("PENDING");
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        } finally { service.close(); }
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
}
