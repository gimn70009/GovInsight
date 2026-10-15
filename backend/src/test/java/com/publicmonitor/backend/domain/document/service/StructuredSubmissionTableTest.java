package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredSubmissionTableTest {
    private static final String TEXT = """
            신청서류
            [표]
            서류명\t주요내용\t구분\t제출조건
            사업계획서\t운영·지원 사업계획서\t필수\t
            증빙자료\t사업계획 내 기재사항 증빙 등\t해당 시\t증빙할 내용이 있는 기관
            발표자료\t사업계획서 기반 발표자료\t선택\t
            기업신용평가등급 확인서\t신청·접수 기간 내 평가된 확인서\t해당 시\t민간기관
            추천서\t\t\t
            [/표]
            """;

    @Test
    void 표의_서류명과_설명을_구별하고_조건이_다음_행에_전파되지_않는다() {
        var guide = SubmissionDocumentResolver.resolve(TEXT, List.of(), null);
        assertThat(guide.documents()).extracting(item -> item.title()).containsExactly(
                "사업계획서", "증빙자료", "발표자료", "기업신용평가등급 확인서", "추천서");
        assertThat(guide.documents().getFirst().requirementLevel()).isEqualTo("MANDATORY");
        assertThat(guide.documents().getFirst().conditions()).isEmpty();
        assertThat(guide.documents().get(1).appliesTo()).isEqualTo("증빙할 내용이 있는 기관");
        assertThat(guide.documents().get(2).requirementLevel()).isEqualTo("OPTIONAL");
        assertThat(guide.documents().get(3).appliesTo()).isEqualTo("민간기관");
        assertThat(guide.documents().getLast().requirementLevel()).isNull();
        assertThat(guide.documents()).allSatisfy(item -> assertThat(TEXT).contains(item.source().excerpt()));
    }

    @Test
    void 파서가_명시적으로_확장한_병합값만_사용하고_빈칸은_미확인으로_둔다() {
        String text = "신청서류\n[표]\n구분\t서류명\t비고\n필수\t신청서\t직인 날인\n"
                + "필수\t사업계획서\t\n\t추천서\t\n[/표]\n현재 안내";
        var guide = SubmissionDocumentResolver.resolve(text, List.of(), null);
        assertThat(guide.documents()).hasSize(3);
        assertThat(guide.documents().get(1).requirementLevel()).isEqualTo("MANDATORY");
        assertThat(guide.documents().getLast().requirementLevel()).isNull();
    }

    @Test
    void 해당_유형의_OX만_연결하고_불완전한_대상행은_확정하지_않는다() {
        String text = "신청서류\n[표]\n제출서류\t유형1\t유형2\n신청서\t○\t○\n확인서\t○\tX\n추천서\t○\t\n[/표]";
        var guide = SubmissionDocumentResolver.resolve(text, List.of(), null);
        assertThat(guide.documents()).hasSize(2);
        assertThat(guide.documents().getFirst().appliesTo()).isEqualTo("유형1 · 유형2");
        assertThat(guide.documents().getLast().appliesTo()).isEqualTo("유형1");
        assertThat(guide.documents().getLast().conditions()).contains("유형2: 제출 대상 아님");
    }

    @Test
    void 공통_면제_조건을_보존하고_잘린_표와_작성예시는_확정하지_않는다() {
        var guide = SubmissionDocumentResolver.resolve(TEXT + "* 개인기업은 사업계획서 제출 면제\n", List.of(), null);
        assertThat(guide.documents().getFirst().conditions()).anyMatch(value -> value.contains("면제"));
        assertThat(SubmissionDocumentResolver.resolve(TEXT.replace("[/표]", ""), List.of(), null).documents()).isEmpty();
        assertThat(SubmissionDocumentResolver.resolve("작성 예시\n" + TEXT, List.of(), null).documents()).isEmpty();
    }

    @Test
    void 실제_초격차_공고의_병합_머리글과_다섯_서류_조건을_보존한다() throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/submission-documents/startup-structured.txt")) {
            text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var guide = SubmissionDocumentResolver.resolve(text, List.of(), null);
        assertThat(guide.documents()).extracting(item -> item.title()).containsExactly(
                "제출 공문", "사업계획서", "증빙자료", "발표자료", "기업신용평가등급 확인서");
        assertThat(guide.documents().subList(0, 3)).allSatisfy(item -> {
            assertThat(item.requirementLevel()).isEqualTo("MANDATORY");
            assertThat(item.formats()).containsExactly("HWP", "PDF");
            assertThat(item.detail()).contains("직인");
        });
        assertThat(guide.documents().get(3).conditions()).anyMatch(value -> value.contains("사업계획서"));
        assertThat(guide.documents().getLast().requirementLevel()).isEqualTo("CONDITIONAL");
        assertThat(guide.documents().getLast().appliesTo()).isEqualTo("민간기관");
    }

    @Test
    void ZIP_내부_양식과_공고의_제출_표를_구별한다() {
        String form = "[파일: 신청서 양식.docx]\n" + "양식 설명\n".repeat(150) + TEXT;
        String notice = "[파일: 모집 공고.hwpx]\n" + TEXT;
        assertThat(SubmissionDocumentResolver.resolve(form, List.of(), null).documents()).isEmpty();
        assertThat(SubmissionDocumentResolver.resolve(form + "\n" + notice, List.of(), null).documents()).hasSize(5);
    }

    @Test
    void 같은_표의_서류마다_명시된_제출_단계를_사용한다() {
        String text = "제출서류\n[표]\n서류명\t구분\t제출시기\n신청서\t필수\t신청 시\n확인서\t필수\t협약 시\n[/표]";
        var guide = SubmissionDocumentResolver.resolve(text, List.of(), null);
        assertThat(guide.documents()).extracting(item -> item.stage()).containsExactly("APPLICATION", "AGREEMENT");
    }
    @Test
    void 실제_CBAM_표의_11개_서류와_유형별_조건_및_공통_유의사항을_보존한다() throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/submission-documents/cbam-structured.txt")) {
            text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var guide = SubmissionDocumentResolver.resolve(text, List.of(), null);
        assertThat(guide.documents()).hasSize(11);
        assertThat(guide.documents()).allSatisfy(item -> {
            assertThat(item.stage()).isEqualTo("APPLICATION");
            assertThat(text).contains(item.source().excerpt());
            assertThat(item.documentNames()).noneMatch(name -> name.contains("[붙임"));
            assertThat(item.conditions()).anyMatch(note -> note.contains("1개월 이내") && note.contains("마감일"));
        });
        assertThat(guide.documents().getFirst().title()).isEqualTo("사업 지원요건 체크리스트");
        assertThat(guide.documents().getFirst().requirementLevel()).isEqualTo("MANDATORY");
        assertThat(guide.documents().getFirst().appliesTo()).isEqualTo("유형1 · 유형2");
        var eu = guide.documents().get(3);
        assertThat(eu.requirementLevel()).isEqualTo("CONDITIONAL");
        assertThat(eu.conditions()).anyMatch(value -> value.contains("해당 시"));
        var financial = guide.documents().getLast();
        assertThat(financial.requirementLevel()).isEqualTo("CONDITIONAL");
        assertThat(financial.appliesTo()).isEqualTo("유형1");
        assertThat(financial.conditions()).contains("유형2: 제출 대상 아님");
        assertThat(guide.notes()).singleElement().satisfies(note -> {
            assertThat(note.text()).contains("1개월 이내", "마감일");
            assertThat(text).contains(note.source().excerpt());
        });
    }

}
