package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.repository.*;
import com.publicmonitor.backend.domain.document.web.dto.ProposalWriteRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProposalSourceServiceTest {
    private final DocumentDetectionRepository detections = mock(DocumentDetectionRepository.class);
    private final DocumentAttachmentRepository attachments = mock(DocumentAttachmentRepository.class);
    private final DocumentVersion version = mock(DocumentVersion.class);
    private final ProposalSourceService service = new ProposalSourceService(detections, attachments);

    @BeforeEach
    void setup() {
        var detection = mock(DocumentDetection.class);
        when(detections.findById(1L)).thenReturn(Optional.of(detection));
        when(detection.getDocumentVersion()).thenReturn(version);
        when(version.getId()).thenReturn(11L);
        when(version.getTitle()).thenReturn("제조 AI 사업");
        when(version.getContentText()).thenReturn("공고 조건");
    }

    private void setAttachments(DocumentAttachment... files) {
        when(attachments.findAllByDocumentVersionId(11L)).thenReturn(List.of(files));
    }

    private DocumentAttachment attachment(Long id, String name, String extension, String text,
            AttachmentParseStatus status) {
        var file = mock(DocumentAttachment.class);
        when(file.getId()).thenReturn(id);
        when(file.getFileName()).thenReturn(name);
        when(file.getFileExtension()).thenReturn(extension);
        when(file.getExtractedText()).thenReturn(text);
        when(file.getParseStatus()).thenReturn(status);
        return file;
    }

    @Test
    void zip_내부_문서를_개별_선택하고_다른_양식의_본문을_섞지_않는다() {
        setAttachments(attachment(
                2L, "제출서류.zip", "zip",
                "[파일: 동의서.pdf]\n개인정보 동의\n\n[파일: 서식/사업계획서.hwpx]\n1. 사업 목표\n2. 수행 방법",
                AttachmentParseStatus.COMPLETED));
        var sources = service.list(1L);
        assertThat(sources).hasSize(2);
        assertThat(sources.get(1).fileName()).isEqualTo("서식/사업계획서.hwpx");
        var input = service.prepare(1L, new ProposalWriteRequest(2L, 1));
        assertThat(input.templateText()).isEqualTo("1. 사업 목표\n2. 수행 방법");
        assertThat(input.templateText()).doesNotContain("개인정보 동의");
        assertThat(input.noticeText()).isEqualTo("공고 조건");
    }

    @Test
    void 현재_감지_버전의_첨부만_허용한다() {
        setAttachments(attachment(
                2L, "양식.pdf", "pdf", "사업 목표", AttachmentParseStatus.COMPLETED));
        assertThatThrownBy(() -> service.prepare(1L, new ProposalWriteRequest(99L, 0)))
                .isInstanceOf(DocumentDetectionException.class).hasMessageContaining("문서 버전");
        assertThatThrownBy(() -> service.prepare(1L, new ProposalWriteRequest(2L, 1)))
                .isInstanceOf(DocumentDetectionException.class);
        verify(attachments, times(2)).findAllByDocumentVersionId(11L);
    }

    @Test
    void 실패_빈본문_길이초과_첨부는_사용불가_사유를_반환하고_생성을_차단한다() {
        setAttachments(
                attachment(2L, "실패.pdf", "pdf", "", AttachmentParseStatus.FAILED),
                attachment(3L, "빈양식.hwpx", "hwpx", " ", AttachmentParseStatus.COMPLETED),
                attachment(4L, "긴양식.pdf", "pdf", "가".repeat(80_001), AttachmentParseStatus.COMPLETED));
        assertThat(service.list(1L)).allSatisfy(item -> {
            assertThat(item.available()).isFalse();
            assertThat(item.reason()).isNotBlank();
        });
        assertThatThrownBy(() -> service.prepare(1L, new ProposalWriteRequest(4L, 0)))
                .isInstanceOf(DocumentDetectionException.class);
    }

    @Test
    void 긴_공고의_앞뒤를_보존하고_선택양식은_자르지_않는다() {
        when(version.getContentText()).thenReturn("시작조건" + "가".repeat(20_000) + "종료조건");
        setAttachments(
                attachment(2L, "양식.pdf", "pdf", "목표와 방법", AttachmentParseStatus.COMPLETED));
        var input = service.prepare(1L, new ProposalWriteRequest(2L, 0));
        assertThat(input.noticeText()).startsWith("시작조건").endsWith("종료조건").hasSizeLessThan(16_001);
        assertThat(input.templateText()).isEqualTo("목표와 방법");
    }

    @Test
    void 없는_감지문서는_조회하지_않는다() {
        assertThatThrownBy(() -> service.list(99L)).isInstanceOf(DocumentDetectionException.class);
        verifyNoInteractions(attachments);
    }

    @Test
    void 공고문_첨부의_조건을_참고하되_작성_양식의_목차는_섞지_않는다() {
        setAttachments(
                attachment(2L, "사업계획서.hwpx", "hwpx", "선택 양식의 목표", AttachmentParseStatus.COMPLETED),
                attachment(3L, "사업공고문.pdf", "pdf", "지원 대상과 접수기한", AttachmentParseStatus.COMPLETED),
                attachment(4L, "다른사업계획서.hwpx", "hwpx", "다른 양식의 목차", AttachmentParseStatus.COMPLETED));
        var input = service.prepare(1L, new ProposalWriteRequest(2L, 0));
        assertThat(input.templateText()).isEqualTo("선택 양식의 목표");
        assertThat(input.noticeText()).contains("사업공고문.pdf", "지원 대상과 접수기한")
                .doesNotContain("다른 양식의 목차", "선택 양식의 목표");
    }
}
