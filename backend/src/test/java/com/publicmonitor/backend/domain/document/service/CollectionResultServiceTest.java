package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.Document;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentVersionRepository;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.CollectedAttachment;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.CollectedDocument;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.SourceResult;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultResponse;
import com.publicmonitor.backend.domain.document.web.dto.CollectionSourceStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunSourceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CollectionResultServiceTest {

    @Mock MonitoringRunRepository runRepository;
    @Mock MonitoringRunSourceRepository runSourceRepository;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock DocumentAttachmentRepository attachmentRepository;
    @Mock DocumentDetectionRepository detectionRepository;

    private CollectionResultService service;
    private MonitoringRun run;
    private MonitoringRunSource runSource;
    private MonitoringSource source;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-18T01:00:00Z"), ZoneOffset.UTC);
        service = new CollectionResultService(
                runRepository,
                runSourceRepository,
                documentRepository,
                versionRepository,
                attachmentRepository,
                detectionRepository,
                new DocumentContentNormalizer(),
                new DocumentVersionHasher(),
                clock
        );
        source = MonitoringSource.create("산업통상부", "사업공고", null, "https://example.com", null, 3, true);
        ReflectionTestUtils.setField(source, "id", 1L);
        run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, LocalDateTime.of(2026, 8, 18, 9, 0));
        ReflectionTestUtils.setField(run, "id", 10L);
        run.accept("3ed1132b-8d61-45d9-bfab-06c1ed96f202", LocalDateTime.of(2026, 8, 18, 9, 1));
        runSource = MonitoringRunSource.create(run, source);
        ReflectionTestUtils.setField(runSource, "id", 20L);
        given(runRepository.findById(10L)).willReturn(Optional.of(run));
        given(runSourceRepository.findByMonitoringRunIdAndMonitoringSourceId(10L, 1L))
                .willReturn(Optional.of(runSource));
    }

    @Test
    void 처음_수집한_문서와_버전_첨부파일_감지결과를_저장한다() {
        given(documentRepository.findByMonitoringSourceIdAndOriginalUrl(1L, "https://example.com/notice/1"))
                .willReturn(Optional.empty());
        given(documentRepository.save(any(Document.class))).willAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            ReflectionTestUtils.setField(document, "id", 100L);
            return document;
        });
        given(versionRepository.findTopByDocumentIdOrderByVersionNoDesc(100L)).willReturn(Optional.empty());
        given(versionRepository.save(any(DocumentVersion.class))).willAnswer(invocation -> {
            DocumentVersion version = invocation.getArgument(0);
            ReflectionTestUtils.setField(version, "id", 200L);
            return version;
        });
        given(detectionRepository.existsByMonitoringRunSourceIdAndDocumentId(20L, 100L)).willReturn(false);
        given(attachmentRepository.saveAll(any())).willAnswer(invocation -> {
            Iterable<?> savedAttachments = invocation.getArgument(0);
            DocumentAttachment attachment = (DocumentAttachment) savedAttachments.iterator().next();
            assertThat(attachment.getFileName()).isEqualTo("붙임 01. 품목요구서 등.zip");
            assertThat(attachment.getFileExtension()).isEqualTo("zip");
            assertThat(attachment.getContentType()).isEqualTo("application/zip");
            assertThat(attachment.getFileSize()).isEqualTo(1024L);
            assertThat(attachment.getFileHash()).isEqualTo("a".repeat(64));
            assertThat(attachment.getExtractedText()).isEqualTo("HWPX 추출 본문");
            assertThat(attachment.getParseStatus()).isEqualTo(AttachmentParseStatus.COMPLETED);
            return List.of(attachment);
        });

        CollectionResultResponse response = service.receive(request("  사업 공고  ", "본문\n내용"));

        assertThat(response.documents()).singleElement().satisfies(result -> {
            assertThat(result.documentId()).isEqualTo(100L);
            assertThat(result.versionId()).isEqualTo(200L);
            assertThat(result.changeType()).isEqualTo(DocumentChangeType.NEW_DOCUMENT);
            assertThat(result.analysisRequired()).isTrue();
        });
        verify(attachmentRepository).saveAll(any());
        verify(detectionRepository).save(any());
        assertThat(runSource.getDetectedDocumentCount()).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(MonitoringRunStatus.COLLECTED);
        assertThat(run.getCompletedAt()).isNull();
    }

    @Test
    void 해시가_같은_문서는_새_버전을_저장하지_않는다() {
        Document document = Document.create(source, "https://example.com/notice/1", "1", LocalDateTime.now());
        ReflectionTestUtils.setField(document, "id", 100L);
        String hash = new DocumentVersionHasher().hash(
                "사업 공고",
                "본문 내용",
                List.of(new CollectedAttachment("붙임 01. 품목요구서 등.zip", "https://example.com/file/1"))
        );
        DocumentVersion version = DocumentVersion.create(
                document, 1, "사업 공고", "본문 내용", hash, null, 1, LocalDateTime.now()
        );
        ReflectionTestUtils.setField(version, "id", 200L);
        DocumentAttachment storedAttachment = DocumentAttachment.create(
                version,
                "붙임 01. 품목요구서 등.zip",
                "https://example.com/file/1",
                "zip",
                null,
                null,
                null
        );
        given(attachmentRepository.findAllByDocumentVersionId(200L)).willReturn(List.of(storedAttachment));
        given(documentRepository.findByMonitoringSourceIdAndOriginalUrl(1L, "https://example.com/notice/1"))
                .willReturn(Optional.of(document));
        given(versionRepository.findTopByDocumentIdOrderByVersionNoDesc(100L)).willReturn(Optional.of(version));
        given(detectionRepository.existsByMonitoringRunSourceIdAndDocumentId(20L, 100L)).willReturn(false);

        CollectionResultResponse response = service.receive(request("사업 공고", "본문  내용"));

        assertThat(response.documents().getFirst().changeType()).isEqualTo(DocumentChangeType.UNCHANGED_DOCUMENT);
        assertThat(response.documents().getFirst().analysisRequired()).isFalse();
        assertThat(storedAttachment.getContentType()).isEqualTo("application/zip");
        assertThat(storedAttachment.getFileSize()).isEqualTo(1024L);
        assertThat(storedAttachment.getFileHash()).isEqualTo("a".repeat(64));
        assertThat(storedAttachment.getExtractedText()).isEqualTo("HWPX 추출 본문");
        verify(versionRepository, never()).save(any());
        verify(attachmentRepository, never()).saveAll(any());
        verify(detectionRepository).save(any());
    }

    private CollectionResultRequest request(String title, String content) {
        CollectedDocument document = new CollectedDocument(
                "https://example.com/notice/1",
                "1",
                title,
                content,
                null,
                List.of(new CollectedAttachment(
                        "붙임 01. 품목요구서 등.zip [11,",
                        "https://example.com/file/1",
                        "application/zip",
                        1024L,
                        "a".repeat(64),
                        "HWPX 추출 본문",
                        AttachmentParseStatus.COMPLETED,
                        null
                ))
        );
        return new CollectionResultRequest(
                10L,
                "3ed1132b-8d61-45d9-bfab-06c1ed96f202",
                List.of(new SourceResult(1L, CollectionSourceStatus.COMPLETED, null, List.of(document)))
        );
    }
}
