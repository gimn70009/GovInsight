package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.publicmonitor.backend.domain.document.client.PythonProposalWriterClient;
import com.publicmonitor.backend.domain.document.client.dto.PythonProposalWriteRequest;
import com.publicmonitor.backend.domain.document.web.dto.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class ProposalWriteServiceTest {
    private final ProposalSourceService sources = mock(ProposalSourceService.class);
    private final PythonProposalWriterClient writer = mock(PythonProposalWriterClient.class);
    private final ProposalDraftStore drafts = mock(ProposalDraftStore.class);
    private final ProposalWriteService service = new ProposalWriteService(sources, writer, drafts);
    private final ProposalWriteRequest request = new ProposalWriteRequest(2L, 0);
    private final ProposalWriteResponse completed = new ProposalWriteResponse("COMPLETED", "양식.hwpx", false,
            List.of(new ProposalWriteResponse.Section("목표", "본문", "원문", "이유", List.of(), List.of())), "");

    @BeforeEach
    void versions() {
        when(drafts.getVersionId(anyLong())).thenReturn(10L);
        when(drafts.getVersionId(2L)).thenReturn(20L);
    }

    @Test
    void 저장된_초안은_원문_준비와_모델_호출_없이_그대로_반환한다() {
        when(drafts.reuse(7L, 1L, request)).thenReturn(Optional.of(completed));
        assertThat(service.write(7L, 1L, request)).isEqualTo(completed);
        verifyNoInteractions(sources, writer);
        verify(drafts, never()).save(any(), any(), any(), any());
    }

    @Test
    void 완료된_초안은_반환_전에_저장한다() {
        prepare(completed);
        when(drafts.save(7L, 1L, request, completed)).thenReturn(completed);
        assertThat(service.write(7L, 1L, request)).isEqualTo(completed);
        var order = inOrder(drafts, sources, writer);
        order.verify(drafts).reuse(7L, 1L, request);
        order.verify(sources).prepare(1L, request);
        order.verify(writer).write(any());
        order.verify(drafts).save(7L, 1L, request, completed);
    }

    @Test
    void 실패와_빈_완료_응답은_저장하지_않는다() {
        prepare(ProposalWriteResponse.unavailable());
        assertThat(service.write(7L, 1L, request).status()).isEqualTo("UNAVAILABLE");
        prepare(new ProposalWriteResponse("COMPLETED", "양식.hwpx", false, List.of(), ""));
        assertThat(service.write(7L, 1L, request).status()).isEqualTo("UNAVAILABLE");
        verify(drafts, never()).save(any(), any(), any(), any());
    }

    @Test
    void 저장에_실패하면_저장_성공으로_응답하지_않는다() {
        prepare(completed);
        when(drafts.save(7L, 1L, request, completed)).thenThrow(new IllegalStateException("test storage failure"));
        assertThatThrownBy(() -> service.write(7L, 1L, request)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 화면을_나가도_진행상태는_계정과_문서버전별로_조회되며_저장완료까지_유지한다() throws Exception {
        prepare(completed);
        var saving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(drafts.save(7L, 1L, request, completed)).thenAnswer(call -> {
            saving.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return completed;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> service.write(7L, 1L, request));
            try {
                assertThat(saving.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(service.state(7L, 1L).running()).containsExactly(
                        new ProposalDraftStateResponse.RunningProposalResponse(2L, 0));
                assertThat(service.state(7L, 3L).running()).hasSize(1);
                assertThat(service.state(8L, 1L).running()).isEmpty();
                assertThat(service.state(7L, 2L).running()).isEmpty();
                assertThat(service.state(7L, 1L).drafts()).isEmpty();
            } finally { release.countDown(); }
            assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo(completed);
            assertThat(service.state(7L, 1L).running()).isEmpty();
            verify(writer, times(1)).write(any());
        }
    }

    @Test
    void 같은_계정_양식의_동시요청은_같은_생성과_저장을_기다린다() throws Exception {
        prepare(completed);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(writer.write(any())).thenAnswer(call -> {
            started.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return completed;
        });
        when(drafts.save(7L, 1L, request, completed)).thenReturn(completed);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.write(7L, 1L, request));
            try {
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> service.write(7L, 3L, request));
                assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(completed);
                assertThat(second.get(3, TimeUnit.SECONDS)).isEqualTo(completed);
            } finally { release.countDown(); }
        }
        verify(writer, times(1)).write(any());
        verify(drafts, times(1)).save(any(), any(), any(), any());
        assertThat(service.state(7L, 1L).running()).isEmpty();
    }

    @Test
    void 생성이나_저장_실패는_진행잠금을_남기지_않고_재시도할_수_있다() {
        prepare(completed);
        when(writer.write(any())).thenThrow(new IllegalStateException("test failure")).thenReturn(completed);
        when(drafts.save(7L, 1L, request, completed)).thenReturn(completed);
        assertThatThrownBy(() -> service.write(7L, 1L, request)).isInstanceOf(IllegalStateException.class);
        assertThat(service.state(7L, 1L).running()).isEmpty();
        assertThat(service.write(7L, 1L, request)).isEqualTo(completed);
        when(drafts.save(7L, 1L, request, completed)).thenThrow(new IllegalStateException("test storage failure"));
        assertThatThrownBy(() -> service.write(7L, 1L, request)).isInstanceOf(IllegalStateException.class);
        assertThat(service.state(7L, 1L).running()).isEmpty();
    }

    @Test
    void 저장결과와_진행상태_조회는_모델을_호출하지_않는다() {
        var saved = new SavedProposalDraftResponse(2L, 0, "양식.hwpx", null, null, completed);
        when(drafts.list(7L, 1L)).thenReturn(List.of(saved));
        assertThat(service.state(7L, 1L)).isEqualTo(new ProposalDraftStateResponse(List.of(saved), List.of()));
        verifyNoInteractions(sources, writer);
    }

    private void prepare(ProposalWriteResponse response) {
        when(drafts.reuse(7L, 1L, request)).thenReturn(Optional.empty());
        var input = new PythonProposalWriteRequest("공고", "공고 본문", "양식.hwpx", "양식 본문");
        when(sources.prepare(1L, request)).thenReturn(input);
        when(writer.write(input)).thenReturn(response);
    }
}
