package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Preparation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParticipationApplicantScopeTest {
    @ParameterizedTest
    @ValueSource(strings = {"지원금 대비 민간부담금 의무매칭(유형별 상이)", "신청기업은 자부담 30% 이상을 부담해야 함", "기관부담연구개발비는 현금과 현물로 매칭함"})
    void 비용_부담_방식은_신청_자격으로_분류하지_않는다(String text) {
        var guide = ParticipationConditionResolver.resolve("□ 지원조건\n" + text, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.section()).isEqualTo("FUNDING");
            assertThat(row.label()).isEqualTo("자부담·사업비");
            assertThat(row.sources().getFirst().excerpt()).contains(text);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"자부담 확보가 가능한 기업만 신청 가능", "지원비율을 충족할 자부담 능력이 있는 기업", "접수 마감일 전까지 자부담 확보 증빙을 제출해야 함", "사업비 관리 계좌 개설이 불가능한 기관은 신청 불가"})
    void 비용과_관련되어도_명시적인_신청_전제는_유지한다(String text) {
        var guide = ParticipationConditionResolver.resolve("□ 신청자격\n" + text, List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> assertThat(row.section()).isEqualTo("BASIC"));
    }

    @Test
    void 자격_하위의_독립_사업비_안내는_원문을_보존하여_분리한다() {
        String original = "□ 신청자격\n- 연구소 보유 기업\n* 민간부담금은 총 사업비의 20% 이상 부담해야 함\n* 단, 비영리기관은 면제";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).hasSize(2).allSatisfy(row -> assertThat(row.sources().getFirst().excerpt()).isEqualTo(original));
        assertThat(guide.rules().stream().filter(row -> row.section().equals("BASIC"))).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("연구소");
            assertThat(row.details()).noneMatch(value -> value.contains("20%"));
        });
        assertThat(guide.rules().stream().filter(row -> row.section().equals("FUNDING"))).singleElement()
                .satisfies(row -> {
                    assertThat(row.text()).contains("20% 이상");
                    assertThat(String.join(" ", row.exceptions())).contains("비영리기관", "면제");
                });
    }

    @Test
    void 이전_저장_분석의_비용_확인사항도_신청_자격으로_되돌아오지_않는다() throws Exception {
        String quote = "중소기업은 총 사업비의 30% 이상을 자부담으로 부담해야 합니다.";
        var mapper = new ObjectMapper();
        var preparation = mapper.readValue(mapper.writeValueAsString(Map.of("eligibilityChecklist", List.of(Map.of(
                "title", "비용 기준", "requirementLevel", "MANDATORY", "source", Map.of("origin", "NOTICE_BODY", "excerpt", quote))))), Preparation.class);
        var guide = ParticipationConditionResolver.resolve(quote, List.of(), preparation);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.section()).isEqualTo("REVIEW");
            assertThat(row.label()).isEqualTo("자부담·사업비");
            assertThat(row.sources().getFirst().excerpt()).isEqualTo(quote);
        });
    }

    @Test
    void 기관_구성의_지역_필수_협력기관_예외는_유지하고_집행_안내는_제외한다() {
        String original = "ㅇ 컨소시엄 구성 요건\n[표]\n기관 구성\t(주관) 부산 소재 기업 ※ 협약 전까지 부산 이전 가능 (공동) 공동연구기관 구성 ※ 공동기관으로 지역 연구소 2개 이상 참여 필수 ※ 다른 지역 대학도 참여 가능\n실증\t(실증) 실증 장소의 장비 사용비 집행 안내\n[/표]";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).hasSize(2).allSatisfy(row -> {
            assertThat(row.section()).isEqualTo("BASIC");
            assertThat(row.sources().getFirst().excerpt()).contains("장비 사용비");
        }).anySatisfy(row -> {
            assertThat(row.text()).contains("지역 연구소 2개 이상");
            assertThat(String.join(" ", row.details())).contains("다른 지역 대학도 참여 가능");
        });
    }

    @Test
    void 제외_가능과_확정_제외를_구별하고_다음_절로_넘어가지_않는다() {
        String original = "8 지원대상 제외 등 유의사항\n< 다음의 경우는 지원 대상에서 제외 처리됨 >\nㅇ 참여제한을 받은 기업\n< 다음의 경우는 지원 대상에서 제외할 수 있음 >\nㅇ 기 지원 과제와 내용이 동일한 경우\n* 단, 성능을 향상시키는 연구개발은 예외\n9 공통적용사항\n사업비 지급 일정";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).hasSize(2).allSatisfy(row -> {
            assertThat(row.section()).isEqualTo("EXCLUSION");
            assertThat(row.sources().getFirst().excerpt()).doesNotContain("사업비 지급");
        }).anySatisfy(row -> {
            assertThat(row.text()).contains("제외될 수 있습니다");
            assertThat(String.join(" ", row.exceptions())).contains("성능을 향상");
        });
    }
}
