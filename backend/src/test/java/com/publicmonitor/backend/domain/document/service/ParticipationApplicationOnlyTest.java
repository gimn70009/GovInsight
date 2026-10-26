package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Preparation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ParticipationApplicationOnlyTest {
    @ParameterizedTest
    @ValueSource(strings = {"지원조건", "지원내용", "사업비", "협약", "사후관리", "평가 기준", "선정 절차", "제출 서류"})
    void 제외_대제목_안의_작은_신청대상_제목도_자격으로_해석하지_않는다(String heading) {
        String original = "1. " + heading + "\n가. 신청대상\n□ 신청자격\n기업부설연구소를 보유한 기업\n"
                + "2. 신청자격\n- 국내 중소기업\n3. 신청 제외\n- 국세 체납 기업";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).hasSize(2).noneMatch(row -> row.text().contains("연구소"));
        assertThat(guide.rules()).anySatisfy(row -> assertThat(row.text()).contains("중소기업"));
        assertThat(guide.rules()).anySatisfy(row -> {
            assertThat(row.section()).isEqualTo("EXCLUSION");
            assertThat(row.text()).contains("체납");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"가. 신청대상", "1. 신청대상", "○ 신청대상"})
    void 새_목록_표시는_지원조건_대제목을_벗어나는_근거가_아니다(String nested) {
        String original = "□ 지원조건\n" + nested + "\n기업부설연구소를 보유해야 함\n"
                + "□ 신청자격\n국내 중소기업";
        assertThat(ParticipationConditionResolver.resolve(original, List.of(), null).rules()).singleElement()
                .satisfies(row -> assertThat(row.text()).contains("국내 중소기업").doesNotContain("연구소"));
    }

    @Test
    void 미보유_제외_조건을_보유_의무로_바꾸지_않는다() {
        assertThat(ParticipationConditionText.displaySentence("사업자등록증 미보유",
                com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationKind.EXCLUSION))
                .doesNotContain("보유해야 합니다");
    }

    @Test
    void 한_근거에서_자격만_분리하고_지원조건과_선정후_의무는_제거한다() {
        String original = "□ 신청자격\n- 업력 3년 이상 중소기업입니다. 지원금액은 기업당 1억원입니다. 선정 후 실적보고서를 제출해야 합니다.\n"
                + "- 기업부설연구소 인정서를 보유해야 함\n* 사업 종료 후 3년간 성과를 보고해야 한다.\n"
                + "- 사업계획서를 제출해야 함\n- 선정된 기업은 고용 인원을 유지해야 함";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).hasSize(2).allSatisfy(row -> {
            assertThat(row.section()).isEqualTo("BASIC");
            assertThat(row.text() + row.details()).doesNotContain("1억원", "보고서", "성과를", "사업계획서", "고용 인원");
            assertThat(row.sources().getFirst().excerpt()).isEqualTo(original);
        });
        assertThat(guide.rules()).anySatisfy(row -> assertThat(row.text()).contains("3년 이상"));
        assertThat(guide.rules()).anySatisfy(row -> assertThat(row.text()).contains("인정서", "보유해야 합니다"));
    }

    @Test
    void 한_문장의_자격과_선정후_의무도_구분한다() {
        var guide = ParticipationConditionResolver.resolve("□ 신청대상\n국내 중소기업이며, 선정 후 사업비 사용 내역을 보고해야 한다.", List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("국내 중소기업").doesNotContain("선정", "보고");
            assertThat(row.details()).isEmpty();
        });
    }

    @Test
    void 집행_안내와_자격이_한_문장에_이어져도_자격만_표시한다() {
        var guide = ParticipationConditionResolver.resolve("□ 신청자격\n연구비로 집행할 수 있으나 신청기업은 연구소를 보유해야 함", List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row ->
                assertThat(row.text()).contains("연구소", "보유해야 합니다").doesNotContain("집행"));
    }

    @Test
    void 혼합_문장의_선정후_의무가_먼저_나와도_뒤의_자격은_보존한다() {
        var guide = ParticipationConditionResolver.resolve("□ 신청자격\n선정 후 성과를 보고해야 한다. 접수 마감일 기준 업력 1년 이상인 기업이어야 함.", List.of(), null);
        assertThat(guide.rules()).singleElement().satisfies(row -> {
            assertThat(row.text()).contains("접수 마감일", "1년 이상", "이어야 합니다").doesNotContain("성과");
        });
    }

    @Test
    void 보유와_제출을_구분하고_제외사유의_예외와_수치는_보존한다() {
        String original = "□ 신청자격\n- 유효한 사업자등록증을 보유해야 한다.\n- 사업자등록증을 제출해야 한다.\n"
                + "- 부산 소재 업력 7년 미만 기업\n□ 신청제외\n- 국세 또는 지방세 체납 기업\n* 단, 접수 마감일까지 완납한 기업은 신청 가능";
        var guide = ParticipationConditionResolver.resolve(original, List.of(), null);
        assertThat(guide.rules()).hasSize(3).noneMatch(row -> row.text().contains("제출"));
        assertThat(guide.rules()).anySatisfy(row -> assertThat(row.text()).contains("보유해야 합니다"));
        assertThat(guide.rules()).anySatisfy(row -> assertThat(row.text()).contains("7년 미만"));
        assertThat(guide.rules()).anySatisfy(row -> {
            assertThat(row.section()).isEqualTo("EXCLUSION");
            assertThat(String.join(" ", row.exceptions())).contains("접수 마감일", "완납", "신청할 수 있습니다");
        });
    }

    @Test
    void 이전_모델_결과로_제외_구간의_문장을_다시_추가하지_않는다() throws Exception {
        String quote = "기업부설연구소를 보유한 기업이어야 한다.";
        var mapper = new ObjectMapper();
        var preparation = mapper.readValue(mapper.writeValueAsString(Map.of("eligibilityChecklist", List.of(Map.of(
                "title", "연구소", "requirementLevel", "MANDATORY", "source", Map.of("origin", "NOTICE_BODY", "excerpt", quote))))), Preparation.class);
        var guide = ParticipationConditionResolver.resolve("□ 지원조건\n○ 신청대상\n" + quote, List.of(), preparation);
        assertThat(guide.rules()).isEmpty();
    }

    @Test
    void 첨부와_ZIP_파일_경계에서_제외_문맥을_초기화한다() {
        String text = "[파일: 안내 공고1.txt]\n□ 사후관리\n○ 신청대상\n매출 100억원 기업\n"
                + "[파일: 안내 공고2.txt]\n□ 신청자격\n국내 중소기업";
        var file = DocumentAttachment.create(null, "통합 공고.zip", "https://example.org/notice.zip", "zip",
                "application/zip", 1L, "hash", text, AttachmentParseStatus.COMPLETED, null);
        assertThat(ParticipationConditionResolver.resolve(null, List.of(file), null).rules()).singleElement()
                .satisfies(row -> assertThat(row.text()).contains("국내 중소기업").doesNotContain("100억원"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"기업부설연구소를 보유하여야 한다", "해당 요건을 충족해야 함", "해당 기업은 신청할 수 없다", "사업자등록증 보유"})
    void 표시_문장은_안내체로_통일한다(String sentence) {
        String display = ParticipationConditionText.displaySentence(sentence,
                com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationKind.MANDATORY);
        assertThat(display).endsWith("니다.").doesNotContain("한다.", "해야 함.");
    }
}