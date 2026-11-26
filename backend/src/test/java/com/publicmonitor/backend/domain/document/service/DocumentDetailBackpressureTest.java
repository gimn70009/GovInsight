package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.publicmonitor.backend.domain.document.client.*;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class DocumentDetailBackpressureTest {
    private DocumentVersion version(String text) {
        var version = DocumentVersion.create(null, 1, "지원 공고", text, "a".repeat(64), null, 0, null);
        ReflectionTestUtils.setField(version, "id", 1L);
        return version;
    }
    private CountDownLatch saturate(Object service) {
        var release = new CountDownLatch(1);
        var pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "executor");
        Runnable blocked = () -> {
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        pool.execute(blocked); pool.execute(blocked);
        for (int i = 0; i < 32; i++) pool.execute(() -> {});
        return release;
    }
    private void resume(Object service, CountDownLatch release) {
        ((ThreadPoolExecutor) ReflectionTestUtils.getField(service, "executor")).getQueue().clear();
        release.countDown();
    }
    @Test void fullParticipationQueueRemainsPendingThenRunsOnceWhenCapacityReturns() throws Exception {
        String quote = "업력 7년 이내 창업기업만 신청 가능";
        var source = new RequirementSource("NOTICE_BODY", null, "신청자격", null, quote);
        var raw = new ParticipationGuide(List.of(), List.of(new ParticipationRule("BASIC", "업력", quote, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(source))), List.of(), List.of());
        var client = mock(PythonParticipationRewriteClient.class); var store = mock(ParticipationRewriteStore.class);
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
        when(client.rewrite(any(), any())).thenAnswer(inv -> ParticipationRewriteContract.Response.unavailable(inv.getArgument(0)));
        var service = new ParticipationRewriteService(client, store, new ObjectMapper());
        var release = saturate(service);
        try {
            var version = version(quote);
            assertThat(service.resolve(version, raw, false).rewriteStatus()).isEqualTo("PENDING");
            verifyNoInteractions(client, store);
            resume(service, release);
            service.resolve(version, raw, false);
            verify(client, timeout(3000).times(1)).rewrite(any(), any());
            verify(store, timeout(3000).times(1)).finish(any(), any(), any());
        } finally { release.countDown(); service.close(); }
    }
    @Test void fullSubmissionQueueRemainsPendingThenRunsOnceWhenCapacityReturns() throws Exception {
        String quote = "제출서류 등에 누락·착오가 있더라도 신청마감일 이후 수정·보완·교체 등 일체 불가";
        var raw = new SubmissionGuide(List.of(), List.of(new SubmissionNote(quote, null,
                new RequirementSource("NOTICE_BODY", null, "제출서류", null, quote))));
        var client = mock(PythonSubmissionNotesClient.class); var store = mock(SubmissionNotesStore.class);
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
        when(client.extract(any())).thenReturn(SubmissionNotesContract.Response.unavailable());
        var service = new SubmissionNotesService(client, store, new ObjectMapper());
        var release = saturate(service);
        try {
            var version = version("제출 유의사항\n" + quote);
            assertThat(service.resolve(version, List.of(), raw).notesStatus()).isEqualTo("PENDING");
            verifyNoInteractions(client, store);
            resume(service, release);
            service.resolve(version, List.of(), raw);
            verify(client, timeout(3000).times(1)).extract(any());
            verify(store, timeout(3000).times(1)).finish(any(), any(), any());
        } finally { release.countDown(); service.close(); }
    }
}
