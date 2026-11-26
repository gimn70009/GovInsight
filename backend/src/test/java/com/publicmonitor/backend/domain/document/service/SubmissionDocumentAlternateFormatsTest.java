package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionDocument;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Preparation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SubmissionDocumentAlternateFormatsTest {
    @Test
    void 실제_주관기관_공고의_HWPX와_양면_PDF를_합쳐도_제출서류는_네개다() throws IOException {
        var hwpx = attachment("주관기관 모집공고.hwpx", fixture("hwpx"));
        var pdf = attachment("주관기관 모집공고.pdf", fixture("pdf"));
        for (var attachments : List.of(List.of(hwpx, pdf), List.of(pdf, hwpx))) {
            var guide = SubmissionDocumentResolver.resolve(null, attachments, null);
            assertThat(guide.documents()).extracting(SubmissionDocument::title).containsExactlyInAnyOrder(
                    "제출 공문", "사업계획서", "(증빙서류) 개인정보 이용동의서", "(증빙서류) 기업신용평가등급 확인서");
            assertThat(guide.documents()).allSatisfy(item -> assertThat(item.stage()).isEqualTo("APPLICATION"));
            assertThat(guide.documents()).filteredOn(item -> item.title().contains("기업신용"))
                    .singleElement().satisfies(item -> {
                        assertThat(item.requirementLevel()).isEqualTo("CONDITIONAL");
                        assertThat(item.conditions()).contains("제출 대상: 민간기관");
                        assertThat(item.source().excerpt()).contains("④", "민간기관");
                    });
            assertThat(guide.documents()).noneSatisfy(item ->
                    assertThat(String.join(" ", item.conditions())).contains("금융거래"));
        }
        var output = Path.of("build/submission-actual/fixture-guide.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writeValueAsString(
                SubmissionDocumentResolver.resolve(null, List.of(hwpx, pdf), null)), StandardCharsets.UTF_8);
    }

    @Test
    void 수정공고의_서로_다른_단계로_잘못_읽힌_PDF도_대표_HWPX만_사용한다() throws IOException {
        var hwpx = attachment("주관기관 모집공고_수정.hwpx", fixture("revised-hwpx"));
        var pdf = attachment("주관기관 모집공고_수정.pdf", fixture("revised-pdf"));
        for (var files : List.of(List.of(hwpx, pdf), List.of(pdf, hwpx))) {
            var result = SubmissionDocumentResolver.resolveWithSources(null, files, null);
            assertThat(result.attachments()).containsExactly(hwpx);
            assertThat(result.guide().documents()).extracting(SubmissionDocument::title).containsExactly(
                    "제출 공문", "사업계획서", "(증빙서류) 개인정보 이용동의서", "(증빙서류) 기업신용평가등급 확인서");
            assertThat(result.guide().documents()).allSatisfy(item -> {
                assertThat(item.stage()).isEqualTo("APPLICATION");
                assertThat(item.source().attachmentName()).isEqualTo(hwpx.getFileName());
            });
            assertThat(result.guide().documents().getLast().conditions()).contains("제출 대상: 민간기관");
            var output = Path.of("build/submission-actual/revised-fixture-guide.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, new ObjectMapper().writeValueAsString(result.guide()), StandardCharsets.UTF_8);
        }
    }

    @Test
    void HWPX가_없어도_모든_지원_형식_조합에서_하나를_고른다() {
        for (var pair : List.of(List.of("hwpx", "hwp"), List.of("hwpx", "pdf"), List.of("hwp", "pdf"),
                List.of("docx", "pdf"), List.of("xlsx", "pdf"))) {
            var preferred = attachment("모집공고." + pair.getFirst(), "신청서류\n① 사업계획서");
            var alternate = attachment("모집공고." + pair.getLast(), "평가 시 제출서류\n① 사업계획서");
            for (var files : List.of(List.of(preferred, alternate), List.of(alternate, preferred))) {
                var result = SubmissionDocumentResolver.resolveWithSources(null, files, null);
                assertThat(result.attachments()).as(pair.toString()).containsExactly(preferred);
                assertThat(result.guide().documents()).singleElement().satisfies(item -> {
                    assertThat(item.source().attachmentName()).isEqualTo(preferred.getFileName());
                    assertThat(item.stage()).isEqualTo("APPLICATION");
                });
            }
        }
    }

    @Test
    void 실패_진행중_빈본문_또는_제출목록을_읽지_못한_우선파일은_다른_형식으로_대체한다() {
        var pdf = attachment("공고.pdf", "신청서류\n① 사업계획서");
        var candidates = new java.util.ArrayList<DocumentAttachment>();
        for (String format : List.of("hwpx", "hwp")) {
            for (var status : AttachmentParseStatus.values()) {
                if (status == AttachmentParseStatus.COMPLETED) continue;
                candidates.add(attachment("공고." + format, "신청서류\n① 동의서", status));
            }
            for (String text : java.util.Arrays.asList(null, "", "  ", "본문만 추출됨",
                    "신청서류\n① 동의서\n[일부 원문 생략]")) {
                candidates.add(attachment("공고." + format, text));
            }
        }
        for (var broken : candidates) {
            var result = SubmissionDocumentResolver.resolveWithSources(null, List.of(broken, pdf), null);
            assertThat(result.attachments()).containsExactly(pdf);
            assertThat(result.guide().documents()).singleElement().satisfies(item ->
                    assertThat(item.source().attachmentName()).isEqualTo("공고.pdf"));
        }
        var failedHwpx = attachment("공고.hwpx", null, AttachmentParseStatus.FAILED);
        var hwp = attachment("공고.hwp", "신청서류\n① 사업계획서");
        assertThat(SubmissionDocumentResolver.resolveWithSources(null, List.of(pdf, failedHwpx, hwp), null)
                .attachments()).containsExactly(hwp);
        assertThat(SubmissionDocumentResolver.resolveWithSources(null, List.of(failedHwpx, hwp), null)
                .attachments()).containsExactly(hwp);
    }

    @Test
    void 이름이_다르거나_같은_확장자이거나_압축파일이면_합치지_않는다() {
        var hwpx = attachment("모집공고.hwpx", "신청서류\n① 사업계획서");
        for (String name : List.of("모집공고_수정.pdf", "모집공고_최종.pdf", "모집 공고.pdf", "모집공고 .pdf",
                "모집공고.hwpx", "모집공고.zip")) {
            var separate = attachment(name, "신청서류\n① 동의서");
            var result = SubmissionDocumentResolver.resolveWithSources(null, List.of(hwpx, separate), null);
            assertThat(result.attachments()).as(name).containsExactly(hwpx, separate);
            assertThat(result.guide().documents()).extracting(SubmissionDocument::title)
                    .containsExactly("사업계획서", "동의서");
        }
        var pdf = attachment("모집공고.PDF", "평가 시 제출서류\n① 사업계획서");
        assertThat(SubmissionDocumentResolver.resolveWithSources(null, List.of(pdf, hwpx), null)
                .attachments()).containsExactly(hwpx);
    }

    @Test
    void 제외한_형식의_저장분석은_재유입하지_않고_선택한_파일과_본문의_보완은_유지한다() {
        var hwpx = attachment("공고.hwpx", "신청서류\n① 사업계획서\n□ 기타 안내\n확약서 제출");
        var pdf = attachment("공고.pdf", "평가 시 제출서류\n① 사업계획서");
        var saved = new ObjectMapper().readValue("""
                {"submissionDocuments":[
                  {"title":"사업계획서","stage":"EVALUATION","source":{"origin":"ATTACHMENT",
                   "attachmentName":"공고.pdf","excerpt":"평가 시 제출서류\\n① 사업계획서"}},
                  {"title":"확약서","stage":"APPLICATION","source":{"origin":"ATTACHMENT",
                   "attachmentName":"공고.hwpx","excerpt":"확약서 제출"}},
                  {"title":"보증서","stage":"AGREEMENT","source":{"origin":"NOTICE_BODY","excerpt":"협약 시 보증서 제출"}}
                ]}
                """, Preparation.class);
        var result = SubmissionDocumentResolver.resolve("협약 시 보증서 제출", List.of(hwpx, pdf), saved);
        assertThat(result.documents()).extracting(SubmissionDocument::title)
                .containsExactly("사업계획서", "확약서", "보증서");
        assertThat(result.documents()).noneSatisfy(item -> assertThat(item.stage()).isEqualTo("EVALUATION"));
    }

    @Test
    void 별도_서식에_근거한_기존_저장_서류도_선택한_출처이면_보존한다() {
        var form = attachment("동의서.hwpx", "개인정보 이용동의서 양식");
        var saved = new ObjectMapper().readValue("""
                {"submissionDocuments":[{"title":"개인정보 이용동의서","stage":"APPLICATION",
                  "source":{"origin":"ATTACHMENT","attachmentName":"동의서.hwpx","excerpt":"개인정보 이용동의서 양식"}}]}
                """, Preparation.class);
        var guide = SubmissionDocumentResolver.resolve(null, List.of(form), saved);
        assertThat(guide.documents()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("개인정보 이용동의서");
            assertThat(item.source().attachmentName()).isEqualTo("동의서.hwpx");
        });
    }

    @Test
    void 실제로_한_문서에_있는_신청과_평가_단계_서류는_유지한다() {
        String text = "신청서류\n① 사업계획서\n평가 시 제출서류\n① 사업계획서";
        var guide = SubmissionDocumentResolver.resolve(null,
                List.of(attachment("공고.pdf", text), attachment("공고.hwpx", text)), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::stage).containsExactly("APPLICATION", "EVALUATION");
    }

    @Test
    void 조건분석도_같은_대표_파일만_사용하고_다른_형식의_실패를_불완전으로_취급하지_않는다() {
        var hwpx = attachment("공고.hwpx", "신청서류\n① 사업계획서\n* 모든 서류는 PDF로 제출");
        var pdf = attachment("공고.pdf", "평가 시 제출서류\n① 동의서", AttachmentParseStatus.FAILED);
        var resolution = SubmissionDocumentResolver.resolveWithSources(null, List.of(pdf, hwpx), null);
        var input = SubmissionNotesProjection.input(null, resolution.attachments(), resolution.guide());
        assertThat(input.origins().values()).singleElement().satisfies(origin ->
                assertThat(origin.fileName()).isEqualTo("공고.hwpx"));
        assertThat(input.request().incomplete()).isFalse();
        var failed = SubmissionDocumentResolver.resolveWithSources(null, List.of(pdf,
                attachment("공고.hwpx", null, AttachmentParseStatus.FAILED)), null);
        assertThat(failed.guide().documents()).isEmpty();
        assertThat(SubmissionNotesProjection.input(null, failed.attachments(), failed.guide()).request().incomplete()).isTrue();
    }

    private static DocumentAttachment attachment(String name, String text) {
        return attachment(name, text, AttachmentParseStatus.COMPLETED);
    }

    private static DocumentAttachment attachment(String name, String text, AttachmentParseStatus status) {
        return DocumentAttachment.create(null, name, "https://example.org/notice", null, null,
                null, null, text, status, null);
    }

    private static String fixture(String format) throws IOException {
        try (var input = SubmissionDocumentAlternateFormatsTest.class.getResourceAsStream(
                "/submission-documents/startup-host-" + format + ".txt")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
