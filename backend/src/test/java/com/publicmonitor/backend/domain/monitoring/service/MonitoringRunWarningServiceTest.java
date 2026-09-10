package com.publicmonitor.backend.domain.monitoring.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.*;
import com.publicmonitor.backend.domain.document.web.dto.CollectionSourceStatus;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringRunNotFoundException;
import com.publicmonitor.backend.domain.monitoring.repository.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MonitoringRunWarningServiceTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 10, 12, 0);
    private final MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 2, now);
    private final MonitoringSource board = MonitoringSource.create("기관", "공고 게시판", null,
            "https://example.org", null, 3, true);
    private final MonitoringRunSource source = MonitoringRunSource.create(run, board);
    private final MonitoringRunRepository runs = mock(MonitoringRunRepository.class);
    private final MonitoringRunSourceRepository sources = mock(MonitoringRunSourceRepository.class);
    private final MonitoringRunWarningService service = new MonitoringRunWarningService(runs, sources);

    @Test
    void 실패한_첨부만_기록하고_기관명이_바뀌어도_수집_당시_문서와_파일을_보존한다() {
        var attachments = List.of(file("신청서.hwpx", AttachmentParseStatus.FAILED, "HWPX 파일에서 텍스트를 찾지 못했습니다."),
                file("로고.png", AttachmentParseStatus.UNSUPPORTED, "unsupported"),
                file("공고.pdf", AttachmentParseStatus.COMPLETED, null));
        var doc = new CollectedDocument("https://example.org/1", "1", "지원사업 모집", "본문", null, attachments);
        MonitoringWarningDetails.record(source, new SourceResult(1L, CollectionSourceStatus.COMPLETED, null, List.of(doc)));
        source.complete(1, 1, now);
        board.update("새 기관", "변경 게시판", null, "https://example.org", null, 3, true);

        assertThat(MonitoringWarningDetails.read(source)).singleElement().satisfies(warning -> {
            assertThat(warning.organizationName()).isEqualTo("기관");
            assertThat(warning.boardName()).isEqualTo("공고 게시판");
            assertThat(warning.stage()).isEqualTo("ATTACHMENT_READ");
            assertThat(warning.documentTitle()).isEqualTo("지원사업 모집");
            assertThat(warning.fileName()).isEqualTo("신청서.hwpx");
            assertThat(warning.message()).contains("텍스트");
            assertThat(warning.count()).isEqualTo(1);
        });
    }

    @Test
    void 게시판_실패는_한_건이며_내부_주소나_인증정보를_상세에_저장하지_않는다() {
        String error = "TimeoutError https://example.org?token=private Authorization: secret C:/private/file";
        MonitoringWarningDetails.record(source, new SourceResult(1L, CollectionSourceStatus.FAILED, error, List.of()));
        source.fail(error, now);
        assertThat(source.getWarningDetailsJson()).doesNotContain("private", "Authorization", "secret", "https://");
        assertThat(MonitoringWarningDetails.read(source)).singleElement().satisfies(warning -> {
            assertThat(warning.stage()).isEqualTo("SOURCE_COLLECTION");
            assertThat(warning.message()).contains("대기 시간");
        });
    }

    @Test
    void 과거_첨부_경고와_손상된_기록은_파일을_추측하지_않고_건수만_안내한다() {
        source.complete(2, 3, now);
        for (String json : new String[] {null, "broken", "[]"}) {
            source.recordWarningDetails(json);
            assertThat(MonitoringWarningDetails.read(source)).singleElement().satisfies(warning -> {
                assertThat(warning.stage()).isEqualTo("LEGACY_DETAILS_UNAVAILABLE");
                assertThat(warning.fileName()).isNull();
                assertThat(warning.count()).isEqualTo(3);
            });
        }
        source.fail("unknown secret", now);
        assertThat(MonitoringWarningDetails.read(source).getFirst().message()).isEqualTo("게시판에 접속하거나 글 목록을 읽지 못했어요.");
    }

    @ParameterizedTest
    @CsvSource({
        "첨부파일 다운로드 시간이 초과되었습니다.,응답 대기 시간이 초과됐어요.",
        "첨부파일 서버가 HTTP 403 상태를 반환했습니다.,요청한 서버가 HTTP 403 오류를 반환했어요.",
        "암호화된 PDF 파일은 처리할 수 없습니다.,암호로 보호된 파일이라 내용을 읽지 못했어요.",
        "첨부파일이 허용된 최대 크기를 초과했습니다.,파일 또는 압축 해제 용량이 처리 한도를 초과했어요.",
        "HWP 본문 레코드가 손상되었습니다.,파일 구조가 손상되어 내용을 읽지 못했어요.",
        "unknown exception with credentials,첨부파일을 다운로드하거나 읽지 못했어요."
    })
    void 수집기_오류를_안전한_설명으로_변환한다(String error, String expected) {
        assertThat(MonitoringWarningDetails.explain(error, false)).isEqualTo(expected);
    }

    @Test
    void 실행의_경고_합계에_맞게_게시판_실패와_과거_첨부_경고를_함께_조회한다() {
        run.completeCollection(1, 1, 2, 3, now);
        source.fail("HTTP 503", now);
        var other = MonitoringRunSource.create(run, board);
        other.complete(2, 2, now);
        when(runs.findById(42L)).thenReturn(Optional.of(run));
        when(sources.findWithSourcesByMonitoringRunId(42L)).thenReturn(List.of(source, other));
        var response = service.find(42L);
        assertThat(response.warningCount()).isEqualTo(3);
        assertThat(response.warnings()).extracting(item -> item.count()).containsExactly(1, 2);
        verify(sources).findWithSourcesByMonitoringRunId(42L);
    }

    @Test
    void 경고가_없으면_상세를_조회하지_않으며_존재하지_않는_실행은_404_예외다() {
        when(runs.findById(42L)).thenReturn(Optional.of(run));
        assertThat(service.find(42L).warnings()).isEmpty();
        assertThatThrownBy(() -> service.find(43L)).isInstanceOf(MonitoringRunNotFoundException.class);
        verifyNoInteractions(sources);
    }

    @Test
    void 소스_상세가_유실된_과거_실행도_경고_합계는_보존한다() {
        run.completeCollection(1, 0, 2, 2, now);
        when(runs.findById(42L)).thenReturn(Optional.of(run));
        when(sources.findWithSourcesByMonitoringRunId(42L)).thenReturn(List.of());
        assertThat(service.find(42L).warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.stage()).isEqualTo("LEGACY_DETAILS_UNAVAILABLE");
            assertThat(warning.count()).isEqualTo(2);
        });
    }

    private CollectedAttachment file(String name, AttachmentParseStatus status, String error) {
        return new CollectedAttachment(name, "https://example.org/file", null, null, null, status, error);
    }
}
