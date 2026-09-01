package com.publicmonitor.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentDetectionRepository;
import com.publicmonitor.backend.domain.analysis.repository.AnalysisDocumentVersionRepository;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.Document;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AnalysisJobRequestServiceTest {

    @Mock AnalysisDocumentDetectionRepository detectionRepository;
    @Mock DocumentAttachmentRepository attachmentRepository;
    @Mock AnalysisDocumentVersionRepository versionRepository;
    @Mock DocumentAnalysisRepository analysisRepository;

    private AnalysisJobRequestService service;
    private Document document;
    private MonitoringRunSource runSource;

    @BeforeEach
    void setUp() {
        service = new AnalysisJobRequestService(
                detectionRepository,
                attachmentRepository,
                versionRepository,
                analysisRepository
        );
        MonitoringSource source = MonitoringSource.create(
                "과학기술정보통신부", "사업공고", null, "https://example.com", null, 3, true
        );
        ReflectionTestUtils.setField(source, "id", 1L);
        MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, LocalDateTime.now());
        ReflectionTestUtils.setField(run, "id", 10L);
        runSource = MonitoringRunSource.create(run, source);
        ReflectionTestUtils.setField(runSource, "id", 20L);
        document = Document.create(source, "https://example.com/notice/1", "1", LocalDateTime.now());
        ReflectionTestUtils.setField(document, "id", 100L);
    }

    @Test
    void 신규_문서와_완료된_첨부파일_텍스트를_분석_요청으로_변환한다() {
        DocumentVersion version = version(1, 200L, "신규 공고", "게시글 본문");
        DocumentDetection detection = detection(version, 300L, DocumentChangeType.NEW_DOCUMENT);
        DocumentAttachment completed = attachment(version, 400L, AttachmentParseStatus.COMPLETED, "첨부 본문");
        DocumentAttachment failed = attachment(version, 401L, AttachmentParseStatus.FAILED, null);

        given(detectionRepository.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(10L))
                .willReturn(List.of(detection));
        given(attachmentRepository.findAllByDocumentVersionId(200L)).willReturn(List.of(completed, failed));

        var request = service.prepare(10L).orElseThrow();

        assertThat(request.runId()).isEqualTo(10L);
        assertThat(request.documents()).singleElement().satisfies(item -> {
            assertThat(item.detectionId()).isEqualTo(300L);
            assertThat(item.changeType()).isEqualTo(DocumentChangeType.NEW_DOCUMENT);
            assertThat(item.organizationName()).isEqualTo("과학기술정보통신부");
            assertThat(item.attachments()).singleElement().satisfies(attachment -> {
                assertThat(attachment.attachmentId()).isEqualTo(400L);
                assertThat(attachment.extractedText()).isEqualTo("첨부 본문");
            });
            assertThat(item.previousVersion()).isNull();
        });
    }

    @Test
    void 기존_분석이_있는_변경없는_문서는_다시_요청하지_않는다() {
        DocumentVersion version = version(1, 200L, "기존 공고", "게시글 본문");
        DocumentDetection detection = detection(version, 300L, DocumentChangeType.UNCHANGED_DOCUMENT);
        given(detectionRepository.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(10L))
                .willReturn(List.of(detection));
        DocumentAnalysis analysis = mock(DocumentAnalysis.class);
        given(analysis.requiresProposalSchemaUpgrade()).willReturn(false);
        given(analysisRepository.findByDocumentVersionId(200L)).willReturn(Optional.of(analysis));

        assertThat(service.prepare(10L)).isEmpty();
    }

    @Test
    void 근거_스키마가_없는_기존_사업제안은_한번_다시_분석한다() {
        DocumentVersion version = version(1, 200L, "기존 사업공고", "게시글 본문");
        DocumentDetection detection = detection(version, 300L, DocumentChangeType.UNCHANGED_DOCUMENT);
        DocumentAnalysis analysis = mock(DocumentAnalysis.class);
        given(analysis.requiresProposalSchemaUpgrade()).willReturn(true);
        given(detectionRepository.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(10L))
                .willReturn(List.of(detection));
        given(analysisRepository.findByDocumentVersionId(200L)).willReturn(Optional.of(analysis));
        given(attachmentRepository.findAllByDocumentVersionId(200L)).willReturn(List.of());

        assertThat(service.prepare(10L).orElseThrow().documents()).hasSize(1);
    }

    @Test
    void 분석이_없는_변경없는_문서는_분석_대상에_포함한다() {
        DocumentVersion version = version(1, 200L, "기존 공고", "게시글 본문");
        DocumentDetection detection = detection(version, 300L, DocumentChangeType.UNCHANGED_DOCUMENT);
        given(detectionRepository.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(10L))
                .willReturn(List.of(detection));
        given(analysisRepository.findByDocumentVersionId(200L)).willReturn(Optional.empty());
        given(attachmentRepository.findAllByDocumentVersionId(200L)).willReturn(List.of());

        assertThat(service.prepare(10L).orElseThrow().documents())
                .singleElement()
                .extracting(item -> item.changeType())
                .isEqualTo(DocumentChangeType.UNCHANGED_DOCUMENT);
    }

    @Test
    void 수정_문서는_직전_버전의_제목과_본문을_함께_전달한다() {
        DocumentVersion previous = version(1, 199L, "이전 제목", "이전 본문");
        DocumentVersion current = version(2, 200L, "수정 제목", "수정 본문");
        DocumentDetection detection = detection(current, 300L, DocumentChangeType.UPDATED_DOCUMENT);
        given(detectionRepository.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(10L))
                .willReturn(List.of(detection));
        given(attachmentRepository.findAllByDocumentVersionId(200L)).willReturn(List.of());
        given(versionRepository.findByDocumentIdAndVersionNo(100L, 1)).willReturn(Optional.of(previous));

        var item = service.prepare(10L).orElseThrow().documents().getFirst();

        assertThat(item.previousVersion()).isNotNull();
        assertThat(item.previousVersion().versionId()).isEqualTo(199L);
        assertThat(item.previousVersion().title()).isEqualTo("이전 제목");
        assertThat(item.previousVersion().contentText()).isEqualTo("이전 본문");
    }

    @Test
    void 분석할_텍스트가_없는_문서는_요청에서_제외한다() {
        DocumentVersion version = version(1, 200L, "본문 없는 공고", null);
        DocumentDetection detection = detection(version, 300L, DocumentChangeType.NEW_DOCUMENT);
        given(detectionRepository.findAllByMonitoringRunSourceMonitoringRunIdOrderByIdAsc(10L))
                .willReturn(List.of(detection));
        given(attachmentRepository.findAllByDocumentVersionId(200L)).willReturn(List.of());

        assertThat(service.prepare(10L)).isEmpty();
    }

    private DocumentVersion version(int versionNo, Long id, String title, String content) {
        DocumentVersion version = DocumentVersion.create(
                document, versionNo, title, content, "a".repeat(64), null, 0, LocalDateTime.now()
        );
        ReflectionTestUtils.setField(version, "id", id);
        return version;
    }

    private DocumentDetection detection(
            DocumentVersion version,
            Long id,
            DocumentChangeType changeType
    ) {
        DocumentDetection detection = DocumentDetection.create(
                runSource, document, version, changeType, LocalDateTime.now()
        );
        ReflectionTestUtils.setField(detection, "id", id);
        return detection;
    }

    private DocumentAttachment attachment(
            DocumentVersion version,
            Long id,
            AttachmentParseStatus status,
            String extractedText
    ) {
        DocumentAttachment attachment = DocumentAttachment.create(
                version,
                "첨부파일.hwpx",
                "https://example.com/file/" + id,
                "hwpx",
                "application/zip",
                100L,
                "b".repeat(64),
                extractedText,
                status,
                status == AttachmentParseStatus.FAILED ? "파싱 실패" : null
        );
        ReflectionTestUtils.setField(attachment, "id", id);
        return attachment;
    }
}
