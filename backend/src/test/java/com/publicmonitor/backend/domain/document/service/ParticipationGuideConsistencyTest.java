package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ParticipationGuideConsistencyTest {
    private String fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/participation-conditions/" + name + ".txt")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private DocumentAttachment file(String name, String text) {
        return DocumentAttachment.create(null, name, "https://example.org/" + name, "hwpx",
                "application/zip", 1L, "hash", text, AttachmentParseStatus.COMPLETED, null);
    }

    @Test
    void 복수_추출본의_표_공백과_번호_제목이_중복이나_가짜_자격을_만들지_않는다() throws Exception {
        String structured = fixture("startup-structured"), legacy = fixture("startup-legacy");
        String spaced = legacy.replace("구분\n신청 자격", "구 분\n신 청 자 격")
                .replace("\n공공기관\n", "\n공 공\n기 관\n").replace("\n민간기관\n", "\n민 간 기 관\n")
                .replace("3\n사업 개요", "3 사업 개요");
        var guide = ParticipationConditionResolver.resolve(null, List.of(file("원본 공고.hwpx", structured),
                file("평문 공고.hwpx", legacy), file("공백 공고.pdf", spaced)), null);
        assertThat(guide.rules().stream().filter(r -> r.label().equals("기관 유형")).toList())
                .hasSize(2).allSatisfy(rule -> {
                    assertThat(rule.sources()).hasSize(3);
                    assertThat(rule.text()).contains("신청 대상은").doesNotContain("공고에 명시된 자격 요건을 충족");
                    assertThat(rule.scope()).isNull();
                });
        assertThat(guide.rules().stream().filter(r -> r.label().equals("지원 역량")).toList()).singleElement()
                .satisfies(rule -> assertThat(rule.text()).contains("전문 기관"));
        assertThat(guide.rules().stream().filter(r -> r.section().equals("EXCLUSION")).toList()).hasSize(8)
                .allSatisfy(rule -> assertThat(rule.label()).isNotEqualTo("참여 자격"));
        assertThat(guide.rules()).allSatisfy(rule -> {
            assertThat(rule.text()).doesNotContain("3 사업", "구분", "신청 기준.");
            assertThat(rule.text()).matches(".*(?:니다|합니다|됩니다)\\.");
            for (var source : rule.sources()) {
                String original = source.attachmentName().startsWith("원본") ? structured :
                        source.attachmentName().startsWith("평문") ? legacy : spaced;
                assertThat(original).contains(source.excerpt());
            }
        });
        Path output = Path.of("build/participation-guide-checks/startup-combined-guide.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writeValueAsString(guide), StandardCharsets.UTF_8);
    }

    @Test
    void 다른_기관_공고에도_공통역량과_기관별_요건을_구분한다() {
        String text = """
                □ 신청자격
                ◦ 해외 판로 지원기능을 보유한 전문 기관
                구 분 신청 자격
                영리기관
                • 관련 교육을 수행하는 법인
                • 사업화 지원을 수행하는 기관
                비영리기관
                • 연구 지원 업무를 수행하는 기관
                • 기술교육을 수행하는 단체
                7 지원내용
                ◦ 지원금 최대 3억원
                """;
        var guide = ParticipationConditionResolver.resolve(text, List.of(), null);
        assertThat(guide.rules()).hasSize(3).noneMatch(rule -> rule.text().contains("지원금"));
        assertThat(guide.rules()).anySatisfy(rule -> {
            assertThat(rule.label()).isEqualTo("기관 유형");
            assertThat(rule.text()).startsWith("영리기관은").endsWith("충족해야 합니다.");
        });
    }

    @Test
    void 읽을_수_없는_역할표는_뒤섞인_자격_문장으로_표시하지_않는다() {
        var guide = ParticipationConditionResolver.resolve("""
                □ 신청 자격
                구분
                신청자격
                머리글이 잘린 셀
                법인 또는 기관
                3 사업 개요
                """, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.section()).isEqualTo("REVIEW");
            assertThat(rule.text()).contains("구분하지 못했습니다");
        });
    }

    @Test
    void 제외사유를_해당_의미의_항목명으로_구분한다() {
        var guide = ParticipationConditionResolver.resolve("""
                □ 신청 제외대상
                ◦ 신청기관(또는 대표자)이 다음 각 호의 어느 하나에 해당하는 경우 지원사업 신청을 제한
                ① 관리기관에 환수금 반환이 종결되지 않은 경우
                ② 창업지원사업 참여 제한으로 제재 중인 경우
                ③ 사업비 계좌 개설 및 회계처리가 불가능한 경우
                ④ 체불사업주 명단에 포함된 경우
                ⑤ 그 밖에 장관이 제한할 사유가 있다고 인정하는 경우
                3 사업 개요
                """, List.of(), null);
        assertThat(guide.rules()).extracting(ParticipationRule::label).containsExactly(
                "환수금 반환", "사업 참여 제재", "사업비 관리", "임금 체불", "기타 신청 제한");
        assertThat(guide.rules()).allSatisfy(rule -> assertThat(rule.scope()).isEqualTo("신청기관 또는 대표자"));
    }

    @Test
    void 불완전한_문장의_나머지를_추측하여_자격을_만들지_않는다() {
        var source = new RequirementSource("NOTICE_BODY", null, "신청자격", null, "사업 지원을");
        var raw = new ParticipationCondition(ParticipationKind.MANDATORY, "참여 자격", "사업 지원을", null, List.of(), source);
        var rules = ParticipationGuidePresentation.rules(List.of(raw));
        assertThat(rules).singleElement().satisfies(rule -> {
            assertThat(rule.section()).isEqualTo("REVIEW");
            assertThat(rule.details()).contains("사업 지원을");
            assertThat(rule.sources()).containsExactly(source);
        });
    }

    @Test
    void 명사형_목록과_제외업종도_의미를_추가하지_않고_문장을_완성한다() {
        assertThat(ParticipationConditionText.displaySentence("법원이 면책을 결정한 자.", null)).endsWith("자입니다.");
        assertThat(ParticipationConditionText.displaySentence("불건전 오락용품 제조업.", ParticipationKind.EXCLUSION))
                .isEqualTo("불건전 오락용품 제조업은 신청이 제한됩니다.");
        assertThat(ParticipationConditionText.displaySentence("일반기업의 신용등급은 B 이상이어야 함", null))
                .isEqualTo("일반기업의 신용등급은 B 이상이어야 합니다.");
    }
    @Test
    void 표_복구가_불확실해도_원문_출처와_도약형_기본조건을_보존한다() throws Exception {
        String original = fixture("policy-loan-pdf");
        var guide = ParticipationConditionResolver.resolve(null, List.of(file("정책자금 공고.pdf", original)), null);
        assertThat(guide.rules()).anySatisfy(rule -> {
            assertThat(rule.section()).isEqualTo("REVIEW");
            assertThat(rule.scope()).contains("재도전특별자금", "도약형");
            assertThat(rule.text()).contains("표에서", "구분하지 못했습니다");
        });
        assertThat(guide.rules()).noneMatch(rule -> rule.text().contains("구분 내용") || rule.text().equals("성실상환:."));
        guide.rules().forEach(rule -> rule.sources().forEach(source -> assertThat(original).contains(source.excerpt())));
    }

    @Test
    void 괄호에_담긴_조건과_제한도_완결문에_그대로_남긴다() {
        assertThat(ParticipationConditionText.displaySentence("소상공인(업력 무관)", ParticipationKind.MANDATORY))
                .isEqualTo("소상공인(업력 무관)이 대상입니다.");
        assertThat(ParticipationConditionText.displaySentence("6개월이 지나지 않은 소상공인(※ 직접대출 한정)", ParticipationKind.EXCLUSION))
                .isEqualTo("6개월이 지나지 않은 소상공인(※ 직접대출 한정)은 신청이 제한됩니다.");
    }

}
