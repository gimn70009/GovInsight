package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Preparation;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SubmissionDocumentResolverTest {
    @Test
    void CBAM_제출표의_11개_서류와_유형별_대상_및_공통_조건을_보존한다() throws IOException {
        var guide = SubmissionDocumentResolver.resolve(null, List.of(attachment("공고.hwpx", fixture("cbam"))), null);
        assertThat(guide.documents()).hasSize(11);
        assertThat(guide.documents()).allSatisfy(item -> {
            assertThat(item.stage()).isEqualTo("APPLICATION");
            assertThat(item.source().origin()).isEqualTo("ATTACHMENT");
            assertThat(item.source().attachmentName()).isEqualTo("공고.hwpx");
        });
        assertThat(guide.documents().get(3).title()).contains("EU 수출실적 증빙양식");
        assertThat(guide.documents().get(3).requirementLevel()).isEqualTo("CONDITIONAL");
        assertThat(guide.documents().getFirst().appliesTo()).isEqualTo("유형1 · 유형2");
        var financial = guide.documents().getLast();
        assertThat(financial.title()).contains("최근 3년간 표준재무제표증명원", "‘23", "’24", "2025");
        assertThat(financial.requirementLevel()).isEqualTo("CONDITIONAL");
        assertThat(financial.appliesTo()).isEqualTo("유형1");
        assertThat(financial.detail()).contains("유형2: 제출 대상 아님");
        assertThat(financial.source().excerpt()).contains("유형1 | 유형2", "○\nX");
        assertThat(guide.notes()).singleElement().satisfies(note -> assertThat(note.text()).contains("1개월 이내", "마감일 기준"));
    }

    @Test
    void 포상_서류_다섯개와_파일형식_날인_안내를_보존하고_중복_목록을_합친다() throws IOException {
        String original = fixture("award");
        var guide = SubmissionDocumentResolver.resolve(original + "\n□ 제출서류\n◦ (서식1) “포상 신청서” 1부\n◦ (서식2) “공적조서” 1부\n□ 서류 작성 유의사항", List.of(), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::title).containsExactly(
                "포상 신청서 1부", "공적조서 1부", "포상에 대한 동의 및 확인서 1부",
                "사업자 등록증 및 법인등기부등본 각 1부", "기타 공적을 인정할 수 있는 증빙서류 각 1부");
        assertThat(guide.documents()).allSatisfy(item -> assertThat(item.stage()).isEqualTo("APPLICATION"));
        assertThat(guide.notes()).anySatisfy(note -> assertThat(note.text()).contains("PDF 및 HWP"));
        assertThat(guide.notes()).anySatisfy(note -> assertThat(note.text()).contains("직인 날인"));
    }

    @Test
    void 같은_서류라도_신청_협약_단계와_대상이_다르면_구별한다() {
        var guide = SubmissionDocumentResolver.resolve("신청서류\n① 사업계획서\n협약 시 제출서류\n① 사업계획서\n② 확약서(선정 기업에 한함)", List.of(), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::stage).containsExactly("APPLICATION", "AGREEMENT", "AGREEMENT");
        assertThat(guide.documents().getLast().requirementLevel()).isEqualTo("CONDITIONAL");
        assertThat(guide.documents().getLast().appliesTo()).contains("선정 기업에 한함");
    }

    @Test
    void 줄바꿈_조건과_선택_서류_및_면제_주석을_보존한다() {
        var guide = SubmissionDocumentResolver.resolve("신청서류\n① 확인서\n해당 기업만 제출\n② 추천서(선택)\n③ 사업자등록증\n* 개인기업은 사업자등록증 제출 면제\n□ 평가 방법", List.of(), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::requirementLevel).containsExactly("CONDITIONAL", "OPTIONAL", "CONDITIONAL");
        assertThat(guide.documents().getFirst().detail()).contains("해당 기업만");
        assertThat(guide.notes()).singleElement().satisfies(note -> assertThat(note.text()).contains("개인기업"));
    }

    @Test
    void 불완전한_대상_행이나_전원_제외_행을_필수로_표시하지_않는다() {
        var guide = SubmissionDocumentResolver.resolve("신청서류\n유형1 유형2\n① 신청서\n○ X\n② 동의서\n○\n③ 확인서\nX X", List.of(), null);
        assertThat(guide.documents()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("신청서");
            assertThat(item.appliesTo()).isEqualTo("유형1");
        });
    }

    @Test
    void 서류명만_있는_첨부나_작성예시를_제출_의무로_추정하지_않는다() {
        var guide = SubmissionDocumentResolver.resolve("첨부 목록\n신청서\n작성 예시\n제출서류\n사업계획서", List.of(
                attachment("신청서.hwpx", "제출서류\n사업계획서"),
                attachment("참고자료.pdf", "제출서류\n동의서"),
                attachment("신청안내.pdf", "첨부 목록\n사업계획서")), null);
        assertThat(guide.documents()).isEmpty();
    }

    @Test
    void 생략_또는_다른_파일_경계에서_잘린_표는_확정하지_않는다() {
        for (String boundary : List.of("[일부 원문 생략]", "[파일: 다음.hwpx]", "x".repeat(200_000))) {
            var guide = SubmissionDocumentResolver.resolve("신청서류\n사업계획서\n" + boundary, List.of(), null);
            assertThat(guide.documents()).isEmpty();
        }
    }

    @Test
    void 파싱_실패_첨부는_읽지_않으며_본문만으로도_제출목록을_제공한다() {
        var failed = DocumentAttachment.create(null, "실패공고.pdf", "https://example.org", "pdf", "application/pdf", 1L, "hash", "제출서류\n동의서", AttachmentParseStatus.FAILED, null);
        var guide = SubmissionDocumentResolver.resolve("제출서류\n① 신청서", List.of(failed), null);
        assertThat(guide.documents()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("신청서");
            assertThat(item.source().origin()).isEqualTo("NOTICE_BODY");
            assertThat(item.stage()).isNull();
        });
    }

    @Test
    void 저장된_근거_서류를_보완하되_원문의_유형_조건과_후속_단계를_유지한다() {
        Preparation preparation = new ObjectMapper().readValue("""
                {"submissionDocuments":[
                  {"title":"재무제표","requirementLevel":"MANDATORY","stage":"APPLICATION","source":{"origin":"NOTICE_BODY","excerpt":"재무제표 제출"}},
                  {"title":"보증서","requirementLevel":"CONDITIONAL","stage":"AGREEMENT","source":{"origin":"NOTICE_BODY","excerpt":"협약 시 보증서 제출"}},
                  {"title":"동의서","requirementLevel":"RECOMMENDED","source":{"origin":"AI_RECOMMENDATION","excerpt":"추천"}},
                  {"title":"추천서","source":{"origin":null,"excerpt":"추천"}},
                  {"title":"추천서","source":{"origin":"NOTICE_BODY","excerpt":" "}}
                ]}
                """, Preparation.class);
        var guide = SubmissionDocumentResolver.resolve("신청서류\n유형1\n유형2\n① 재무제표\n○\nX", List.of(), preparation);
        assertThat(guide.documents()).hasSize(2);
        assertThat(guide.documents().getFirst().requirementLevel()).isEqualTo("CONDITIONAL");
        assertThat(guide.documents().getLast().stage()).isEqualTo("AGREEMENT");
    }

    @Test
    void 목록_뒤의_작성예시는_앞의_유효한_서류를_지우거나_예시_서류를_추가하지_않는다() {
        var guide = SubmissionDocumentResolver.resolve("신청서류\n① 사업계획서\n작성 예시\n신청서류\n① 동의서", List.of(), null);
        assertThat(guide.documents()).extracting(SubmissionDocument::title).containsExactly("사업계획서");
    }

    @Test
    void 공통_선택_또는_조건부_안내가_있으면_필수로_표시하지_않는다() {
        for (String note : List.of("모든 서류는 해당 기업만 제출", "전체 서류는 선택 제출")) {
            var guide = SubmissionDocumentResolver.resolve("신청서류\n① 사업계획서\n※ " + note, List.of(), null);
            assertThat(guide.documents()).singleElement().satisfies(item -> assertThat(item.requirementLevel())
                    .isEqualTo(note.contains("선택") ? "OPTIONAL" : "CONDITIONAL"));
            assertThat(guide.notes()).singleElement().satisfies(item -> assertThat(item.text()).contains(note));
        }
    }

    private static String fixture(String name) throws IOException {
        try (var stream = SubmissionDocumentResolverTest.class.getResourceAsStream("/submission-documents/" + name + ".txt")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static DocumentAttachment attachment(String name, String text) {
        return DocumentAttachment.create(null, name, "https://example.org/notice", "hwpx", "application/zip", 1L, "hash", text, AttachmentParseStatus.COMPLETED, null);
    }
}
