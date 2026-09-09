package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.repository.*;
import com.publicmonitor.backend.domain.document.web.dto.ProposalWriteRequest;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test
    void 기존_zip의_깨진_파일명을_조회와_초안입력에서_복구하고_원문과_선택번호를_유지한다() {
        var names = List.of("붙임 1. 신청서 양식.hwpx", "붙임 2. 신청서 영문요약 양식.hwpx", "붙임 3. 별첨 양식.hwpx");
        var stored = new StringBuilder();
        for (int index = 0; index < names.size(); index++) {
            String broken = new String(names.get(index).getBytes(Charset.forName("x-windows-949")),
                    Charset.forName("IBM437"));
            assertThat(broken).isNotEqualTo(names.get(index));
            stored.append("[파일: ").append(broken).append("]\n본문 ").append(index).append("\n\n");
        }
        var zip = attachment(2L, "제출서류.zip", "zip", stored.toString(), AttachmentParseStatus.COMPLETED);
        setAttachments(zip);

        var sources = service.list(1L);
        assertThat(sources).extracting(item -> item.fileName()).containsExactlyElementsOf(names);
        assertThat(sources).allSatisfy(item -> {
            assertThat(item.attachmentId()).isEqualTo(2L);
            assertThat(item.attachmentName()).isEqualTo("제출서류.zip");
            assertThat(item.available()).isTrue();
        });
        for (int index = 0; index < names.size(); index++) {
            assertThat(sources.get(index).partIndex()).isEqualTo(index);
            var input = service.prepare(1L, new ProposalWriteRequest(2L, index));
            assertThat(input.fileName()).isEqualTo(names.get(index));
            assertThat(input.templateText()).isEqualTo("본문 " + index);
        }
        assertThat(zip.getExtractedText()).isEqualTo(stored.toString());
        verify(attachments, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"서식/한글 양식.hwpx", "form.pdf", "café.hwpx", "éé.hwpx", "╡.hwpx",
            "╩í.hwpx", "日本語.hwpx", "한글─양식.hwpx"})
    void 정상_이름과_복구할_수_없는_이름은_그대로_유지한다(String name) {
        setAttachments(attachment(2L, "제출서류.zip", "zip", "[파일: " + name + "]\n양식 본문",
                AttachmentParseStatus.COMPLETED));
        assertThat(service.list(1L).getFirst().fileName()).isEqualTo(name);
        assertThat(service.prepare(1L, new ProposalWriteRequest(2L, 0)).fileName()).isEqualTo(name);
    }

    @Test
    void zip_외부_첨부파일명은_복구_대상에서_제외한다() {
        String name = new String("신청서.hwpx".getBytes(Charset.forName("x-windows-949")),
                Charset.forName("IBM437"));
        setAttachments(attachment(2L, name, "hwpx", "본문", AttachmentParseStatus.COMPLETED));
        assertThat(service.list(1L).getFirst().fileName()).isEqualTo(name);
    }
    @Test
    void 동일_본문의_파일명과_미지원_실패_사유를_표시하고_선택한_본문만_전달한다() {
        var file = attachment(2L, "서식.zip", "zip", "[파일: 양식.hwpx]\n작성 본문", AttachmentParseStatus.COMPLETED);
        when(file.getArchiveEntriesJson()).thenReturn("""
                [{"fileName":"양식.hwp","status":"DUPLICATE","partIndex":0},
                 {"fileName":"양식.hwpx","status":"COMPLETED","partIndex":0},
                 {"fileName":"설명.docx","status":"UNSUPPORTED","partIndex":null},
                 {"fileName":"고장.pdf","status":"FAILED","partIndex":null}]
                """);
        setAttachments(file);
        var sources = service.list(1L);
        assertThat(sources).hasSize(3);
        assertThat(sources.getFirst().relatedFileNames()).containsExactly("양식.hwp", "양식.hwpx");
        assertThat(sources.get(1).reason()).startsWith("미지원 형식");
        assertThat(sources.get(2).reason()).startsWith("읽기 실패");
        assertThat(service.prepare(1L, new ProposalWriteRequest(2L, 0)).templateText()).isEqualTo("작성 본문");
        assertThatThrownBy(() -> service.prepare(1L, new ProposalWriteRequest(2L, 1)))
                .isInstanceOf(DocumentDetectionException.class);
        assertThatThrownBy(() -> service.prepare(1L, new ProposalWriteRequest(2L, 2)))
                .isInstanceOf(DocumentDetectionException.class);
    }

    @Test
    void 모든_파일이_미지원이어도_파일별_이유를_반환한다() {
        var file = attachment(2L, "서식.zip", "zip", "", AttachmentParseStatus.UNSUPPORTED);
        when(file.getArchiveEntriesJson()).thenReturn("""
                [{"fileName":"양식.docx","status":"UNSUPPORTED"},{"fileName":"표.xlsx","status":"UNSUPPORTED"}]
                """);
        setAttachments(file);
        assertThat(service.list(1L)).hasSize(2).allSatisfy(source -> assertThat(source.available()).isFalse());
    }

}
