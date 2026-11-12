package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.publicmonitor.backend.domain.document.client.SubmissionNotesContract.*;
import com.publicmonitor.backend.domain.document.client.PythonSubmissionNotesClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class SubmissionNotesServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String quote = "제출서류 등에 누락·착오가 있더라도 신청마감일 이후 수정·보완·교체 등 일체 불가";
    private final String contact = " \uF06F 신청·접수 시스템(K-Startup) 문의 :국번없이 1357 (시스템운영팀).";
    private final String text = "제출서류 등에 누락·착오가 있더라도 신청마감일 이후 수정·보완·교체 등은 일체 불가합니다.";
    private DocumentVersion version() {
        var v = DocumentVersion.create(null, 1, "공고", "제출 유의사항\n" + quote + contact, "a".repeat(64), null, 0, null);
        ReflectionTestUtils.setField(v, "id", 1L);
        return v;
    }
    private SubmissionGuide raw() {
        return new SubmissionGuide(List.of(), List.of(new SubmissionNote(quote + contact, null,
                new RequirementSource("NOTICE_BODY", null, "제출서류", null, quote + contact))));
    }
    private Response result() {
        return new Response(List.of(new Note("s0", quote, text, "COMMON", null, "제출 유의사항", null)), "READY");
    }
    private SubmissionGuide terminal(SubmissionNotesService service, DocumentVersion version) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        SubmissionGuide value;
        do {
            value = service.resolve(version, List.of(), raw());
            if (!"PENDING".equals(value.notesStatus())) return value;
            Thread.sleep(10);
        } while (System.nanoTime() < end);
        throw new AssertionError("Submission notes did not finish");
    }
    @Test void wholeContextSingleFlightAndPersistentReuse() throws Exception {
        var client = mock(PythonSubmissionNotesClient.class);
        var store = mock(SubmissionNotesStore.class);
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(client.extract(any())).thenAnswer(inv -> {
            Request req = inv.getArgument(0);
            assertThat(req.sources().getFirst().text()).contains("1357", "제출 유의사항", "\uF06F");
            entered.countDown(); release.await(5, TimeUnit.SECONDS); return result();
        });
        var service = new SubmissionNotesService(client, store, mapper);
        var version = version();
        try {
            assertThat(service.resolve(version, List.of(), raw()).notes()).isEmpty();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 5; i++) assertThat(service.resolve(version, List.of(), raw()).notesStatus()).isEqualTo("PENDING");
            verify(client, times(1)).extract(any());
            release.countDown();
            var ready = terminal(service, version);
            assertThat(ready.notesStatus()).isEqualTo("READY");
            assertThat(ready.notes()).singleElement().satisfies(n -> {
                assertThat(n.text()).isEqualTo(text).doesNotContain("1357", "문의", "\uF06F");
                assertThat(n.source().excerpt()).isEqualTo("제출 유의사항\n" + quote);
            });
            var input = SubmissionNotesProjection.input(version.getContentText(), List.of(), raw());
            ReflectionTestUtils.setField(version, "submissionNotesKey", service.fingerprint(input.request()));
            ReflectionTestUtils.setField(version, "submissionNotesJson", mapper.writeValueAsString(
                    new SubmissionNotesService.Stored(result(), System.currentTimeMillis())));
            var restartedClient = mock(PythonSubmissionNotesClient.class);
            var restarted = new SubmissionNotesService(restartedClient, store, mapper);
            try {
                assertThat(restarted.resolve(version, List.of(), raw())).isEqualTo(ready);
                verifyNoInteractions(restartedClient);
            } finally { restarted.close(); }
        } finally { release.countDown(); service.close(); }
    }
    @Test void failuresAreTerminalAndDoNotLeakRawMixedNotes() throws Exception {
        var client = mock(PythonSubmissionNotesClient.class); var store = mock(SubmissionNotesStore.class);
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
        when(client.extract(any())).thenThrow(new IllegalStateException("provider unavailable"));
        var service = new SubmissionNotesService(client, store, mapper);
        try {
            var version = version(); var result = terminal(service, version);
            assertThat(result.notesStatus()).isEqualTo("UNAVAILABLE");
            assertThat(result.notes()).isEmpty();
            for (int i = 0; i < 4; i++) assertThat(service.resolve(version, List.of(), raw())).isEqualTo(result);
            verify(client, times(1)).extract(any());
            verify(store).finish(eq(1L), anyString(), contains("UNAVAILABLE"));
        } finally { service.close(); }
    }
    @Test void abandonedOrCorruptSavedAttemptsNeverRetryPaidCall() {
        var client = mock(PythonSubmissionNotesClient.class);
        var service = new SubmissionNotesService(client, mock(SubmissionNotesStore.class), mapper);
        try {
            var version = version();
            var input = SubmissionNotesProjection.input(version.getContentText(), List.of(), raw());
            ReflectionTestUtils.setField(version, "submissionNotesKey", service.fingerprint(input.request()));
            ReflectionTestUtils.setField(version, "submissionNotesJson", mapper.writeValueAsString(
                    new SubmissionNotesService.Stored(new Response(List.of(), "PENDING"), System.currentTimeMillis() - 121000)));
            assertThat(service.resolve(version, List.of(), raw()).notesStatus()).isEqualTo("UNAVAILABLE");
            ReflectionTestUtils.setField(version, "submissionNotesJson", "invalid");
            assertThat(service.resolve(version, List.of(), raw()).notesStatus()).isEqualTo("UNAVAILABLE");
            verifyNoInteractions(client);
        } finally { service.close(); }
    }
    @Test void databaseClaimMustSucceedBeforeCallingModel() throws Exception {
        var client = mock(PythonSubmissionNotesClient.class); var store = mock(SubmissionNotesStore.class);
        var reached = new CountDownLatch(1);
        when(store.claim(any(), anyString(), any(), anyString())).thenAnswer(inv -> { reached.countDown(); return false; });
        var service = new SubmissionNotesService(client, store, mapper);
        try {
            service.resolve(version(), List.of(), raw());
            assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(client);
            verify(store, never()).finish(any(), any(), any());
        } finally { service.close(); }
    }
    @Test void changedRawSourceInvalidatesFingerprint() {
        var service = new SubmissionNotesService(mock(PythonSubmissionNotesClient.class), mock(SubmissionNotesStore.class), mapper);
        try {
            var before = SubmissionNotesProjection.input(quote, List.of(), raw()).request();
            var after = SubmissionNotesProjection.input(quote + " 단, 사전 승인 시 보완 가능.", List.of(), raw()).request();
            assertThat(service.fingerprint(before)).isNotEqualTo(service.fingerprint(after));
        } finally { service.close(); }
    }
    @Test void foreignQuotesUnknownDocumentsAndChangedNumbersAreRejected() {
        String row = "신청서: 접수일 이전 3개월 이내 발급분만 제출. 다만 개인은 제출 면제";
        var doc = new SubmissionDocument("신청서", null, "CONDITIONAL", "APPLICATION", null,
                new RequirementSource("NOTICE_BODY", null, "제출서류", "행 1", row));
        var raw = new SubmissionGuide(List.of(doc), List.of());
        var input = SubmissionNotesProjection.input(row, List.of(), raw);
        String validText = "신청서는 접수일 이전 3개월 이내 발급분만 제출합니다. 다만 개인은 제출 면제입니다.";
        var valid = new Note("s0", row, validText, "DOCUMENT", "d0", row, null);
        var guide = SubmissionNotesProjection.guide(raw, input, new Response(List.of(valid), "READY"));
        assertThat(guide.notesStatus()).isEqualTo("READY");
        assertThat(guide.notes().getFirst().appliesTo()).isEqualTo("신청서");
        assertThat(guide.notes().getFirst().source().location()).isEqualTo("행 1");
        for (var bad : List.of(
                new Note("s9", row, validText, "DOCUMENT", "d0", row, null),
                new Note("s0", row, validText, "DOCUMENT", "d99", row, null),
                new Note("s0", row, validText.replace("3개월", "6개월"), "DOCUMENT", "d0", row, null),
                new Note("s0", row, validText.replace("3개월", "3년"), "DOCUMENT", "d0", row, null),
                new Note("s0", row, validText.replace("이전", "이후"), "DOCUMENT", "d0", row, null),
                new Note("s0", row, "신청서는 접수일 이전 3개월 이내 발급분을 제출합니다.", "DOCUMENT", "d0", row, null),
                new Note("s0", row, validText, "DOCUMENT", "d0", "만들어낸 표제", null))) {
            var rejected = SubmissionNotesProjection.guide(raw, input, new Response(List.of(bad), "READY"));
            assertThat(rejected.notesStatus()).isEqualTo("PARTIAL");
            assertThat(rejected.notes()).isEmpty();
        }
    }
}
