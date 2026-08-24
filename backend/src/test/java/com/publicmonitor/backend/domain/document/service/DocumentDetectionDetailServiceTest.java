package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisFavorability;
import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.Document;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DocumentDetectionDetailServiceTest {

    @Mock DocumentDetectionRepository detectionRepository;
    @Mock DocumentAnalysisRepository analysisRepository;
    @Mock DocumentAttachmentRepository attachmentRepository;

    @Test
    void 프론트가_바로_사용할_수_있는_구조화된_상세를_반환한다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 9, 0);
        MonitoringSource source = MonitoringSource.create(
                "과학기술정보통신부", "사업공고", null, "https://example.com", null, 2, true
        );
        ReflectionTestUtils.setField(source, "id", 1L);
        MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now);
        MonitoringRunSource runSource = MonitoringRunSource.create(run, source);
        Document document = Document.create(source, "https://example.com/notice/1", "1", now);
        ReflectionTestUtils.setField(document, "id", 2L);
        DocumentVersion version = DocumentVersion.create(
                document, 1, "AI 지원사업", "본문", "a".repeat(64), now, 1, now
        );
        ReflectionTestUtils.setField(version, "id", 3L);
        DocumentDetection detection = DocumentDetection.create(
                runSource, document, version, DocumentChangeType.NEW_DOCUMENT, now
        );
        ReflectionTestUtils.setField(detection, "id", 4L);
        DocumentAnalysis analysis = DocumentAnalysis.create(
                version,
                "제조 AI 사업화를 지원하는 공고입니다.",
                "[\"신청 자격 확인\",\"제출 기한 확인\"]",
                DocumentImportance.HIGH,
                "신청 기한이 있어 빠른 검토가 필요합니다.",
                AnalysisEligibility.REVIEW_REQUIRED,
                AnalysisFavorability.NOT_APPLICABLE,
                "제조 데이터 분석 역량을 활용한 참여 방향을 검토합니다.",
                "[\"get_document_content\",\"get_company_profile\"]",
                "mock-model",
                now
        );
        DocumentAttachment attachment = DocumentAttachment.create(
                version, "공고문.hwpx", "https://example.com/file/1", "hwpx",
                "application/zip", 100L, "b".repeat(64), "추출 본문",
                AttachmentParseStatus.COMPLETED, null
        );
        ReflectionTestUtils.setField(attachment, "id", 5L);
        given(detectionRepository.findById(4L)).willReturn(Optional.of(detection));
        given(analysisRepository.findByDocumentVersionId(3L)).willReturn(Optional.of(analysis));
        given(attachmentRepository.findAllByDocumentVersionId(3L)).willReturn(List.of(attachment));

        var response = service().findById(4L);

        assertThat(response.organizationName()).isEqualTo("과학기술정보통신부");
        assertThat(response.title()).isEqualTo("AI 지원사업");
        assertThat(response.attachments()).hasSize(1);
        assertThat(response.analysis().keyPoints()).containsExactly("신청 자격 확인", "제출 기한 확인");
        assertThat(response.analysis().eligibility()).isEqualTo(AnalysisEligibility.REVIEW_REQUIRED);
        assertThat(response.attachments()).singleElement().satisfies(item ->
                assertThat(item.parseStatus()).isEqualTo(AttachmentParseStatus.COMPLETED)
        );
    }

    @Test
    void 존재하지_않는_감지_문서는_예외를_반환한다() {
        given(detectionRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().findById(999L))
                .isInstanceOf(DocumentDetectionException.class);
    }

    private DocumentDetectionDetailService service() {
        return new DocumentDetectionDetailService(
                detectionRepository,
                analysisRepository,
                attachmentRepository,
                new ObjectMapper()
        );
    }
}
