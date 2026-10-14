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

    @Test
    void 클린제조환경_원문의_빈_가점서류를_공통_유의사항으로_표시하지_않는다() throws IOException {
        var guide = SubmissionDocumentResolver.resolve(null, List.of(attachment("클린제조환경_공고.hwpx", fixture("clean"))), null);
        // Keep the resolver output available for the offline browser check of this real notice.
        var output = java.nio.file.Path.of("build/submission-note-checks/clean-guide.json");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, new ObjectMapper().writeValueAsString(guide), StandardCharsets.UTF_8);
        assertThat(guide.notes()).noneSatisfy(note -> assertThat(note.text()).contains("제출서류 없음"));
        assertThat(guide.notes()).anySatisfy(note -> {
            assertThat(note.appliesTo()).isEqualTo("중소기업(소상공인) 확인서");
            assertThat(note.text()).isEqualTo("신청일 기준으로 유효한 서류여야 합니다. ‘소상공인’임이 명시되어 있어야 합니다.");
            assertThat(note.source().excerpt()).contains("□ 중소기업(소상공인) 확인서", "* 신청일 기준 기간유효 必, 소상공인 명시 必");
        });
        assertThat(guide.notes()).anySatisfy(note -> {
            assertThat(note.text()).contains("제공을 요구하지 않은 경우", "7~11번", "동의한 경우에는 제출하지 않습니다");
            assertThat(note.appliesTo()).isNull();
        });
        assertThat(guide.notes()).anySatisfy(note -> {
            assertThat(note.text()).isEqualTo("가점 항목마다 필요한 서류가 다릅니다. 8쪽의 가점 항목을 확인해 주세요.");
            assertThat(note.source().excerpt()).contains("8p 가점항목 참고");
        });
    }

    @Test
    void 같은_발급조건도_적용서류가_다르면_대상을_보존한다() {
        var guide = SubmissionDocumentResolver.resolve("신청서류\n① 사업자등록증명원\n* 신청일로부터 1개월 이내 발급분\n② 납세증명서\n* 신청일로부터 1개월 이내 발급분", List.of(), null);
        assertThat(guide.notes()).hasSize(2);
        assertThat(guide.notes()).extracting(note -> note.appliesTo()).containsExactly("사업자등록증명원", "납세증명서");
        assertThat(guide.notes()).allSatisfy(note -> {
            assertThat(note.text()).isEqualTo("신청일로부터 1개월 이내에 발급된 서류여야 합니다.");
            assertThat(note.source().excerpt()).contains(note.appliesTo(), "* 신청일로부터 1개월 이내 발급분");
        });
    }

    @Test
    void 연결할_서류가_없거나_관련없는_주석을_공통조건으로_승격하지_않는다() {
        var guide = SubmissionDocumentResolver.resolve("제출서류\n* 신청일 기준 기간유효 必\n(제출서류 없음)\n① 사업계획서\n* 선정규모는 50개사\n* 문의전화 1234\n2\n* 신청일 기준 기간유효 必", List.of(), null);
        assertThat(guide.notes()).isEmpty();
    }

    @Test
    void 공통조건과_특정기업_면제조건은_원문_대상과_예외를_유지한다() {
        var guide = SubmissionDocumentResolver.resolve("신청서류\n① 사업자등록증\n② 동의서\n* 개인기업은 사업자등록증 제출 면제\n* 모든 서류는 마감일 기준 유효한 경우만 인정", List.of(), null);
        assertThat(guide.notes()).hasSize(2);
        assertThat(guide.notes().getFirst().appliesTo()).isEqualTo("사업자등록증");
        assertThat(guide.notes().getFirst().text()).contains("개인기업", "제출 면제");
        assertThat(guide.notes().getLast().appliesTo()).isNull();
        assertThat(guide.notes().getLast().text()).contains("모든 서류", "마감일 기준", "유효한 경우만");
    }

    @Test
    void 숫자_문장부호가_다른_조건을_중복으로_합치지_않는다() {
        var guide = SubmissionDocumentResolver.resolve("신청서류\n① 확인서\n* 유효기간 1.5개월\n* 유효기간 15개월\n* 유효기간 1.5개월", List.of(), null);
        assertThat(guide.notes()).extracting(note -> note.text()).containsExactly("유효기간 1.5개월.", "유효기간 15개월.");
    }

    @Test
    void 유형별_조건은_대상과_원문_표식을_함께_보존한다() {
        var guide = SubmissionDocumentResolver.resolve("신청서류\n유형1 유형2\n① 확인서\n○ X\n* 신청일 기준 기간유효 必", List.of(), null);
        assertThat(guide.notes()).singleElement().satisfies(note -> {
            assertThat(note.appliesTo()).isEqualTo("확인서 · 유형1");
            assertThat(note.source().excerpt()).contains("유형1 | 유형2", "○ X", "* 신청일 기준 기간유효 必");
        });
    }

    @Test
    void 별도_주어가_있는_조건을_직전_서류에_연결하지_않는다() {
        var guide = SubmissionDocumentResolver.resolve("제출서류\n① 사업계획서\n* 풍수해보험 보험기간이 신청마감일 기준 유효한 경우 인정", List.of(), null);
        assertThat(guide.notes()).isEmpty();
    }

    @Test
    void 아래_목록_조건을_앞선_서류에_소급_적용하지_않는다() {
        var guide = SubmissionDocumentResolver.resolve("제출서류\n① 사업계획서\n※ 아래 추가 서류 제출은 해당 기업만 제출", List.of(), null);
        assertThat(guide.documents()).singleElement().satisfies(item -> assertThat(item.requirementLevel()).isEqualTo("MANDATORY"));
        assertThat(guide.notes()).singleElement().satisfies(item -> assertThat(item.text()).contains("해당 기업만"));
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
