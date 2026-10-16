package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BusinessSummaryEmailViewTest {
    @Test
    void 공고_성격을_메일에_그대로_표시한다() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        for (String label : java.util.List.of("일반 공고 · 결과 발표", "일반 공고 · 규정 고시", "포상·표창 모집", "지정 모집")) {
            var payload = mapper.writeValueAsString(java.util.Map.of("analysisPending", false, "businessType", java.util.Map.of(
                    "status", "NOT_APPLICABLE", "noticeNature", java.util.Map.of("code", "AWARD", "label", label))));
            var detail = mapper.readValue(payload, com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.class);
            assertThat(BusinessSummaryEmailView.from(detail).type()).isEqualTo(label);
        }
    }

    @Test
    void 웹_상세의_같은_필드에서_여섯_항목과_기관_역할을_읽는다() {
        var view = BusinessSummaryEmailView.from(BusinessSummaryEmailFixtures.jumpUp());
        assertThat(view.type()).isEqualTo("조사·컨설팅·전문지원");
        assertThat(view.sourceOrganization()).isEqualTo("중소벤처기업부");
        assertThat(view.organizations()).extracting(BusinessSummaryEmailView.Organization::name)
                .containsExactly("중소벤처기업진흥공단", "이노비즈협회");
        assertThat(view.organizations()).extracting(BusinessSummaryEmailView.Organization::role).containsOnly("문의처");
        assertThat(view.purpose()).contains("중견기업으로 도약").doesNotContain("이전의 긴 보고서용 요약");
        assertThat(view.supportScale()).contains("2억 원", "2억 8,000만 원", "7억 5,000만 원", "220개사");
        assertThat(view.deadline()).isEqualTo("2026-11-13 17:00 (온라인 통합정보관리시스템 제출 완료 기준).");
        assertThat(view.score()).isEqualTo(72);
    }

    @Test
    void 웹과_같이_요약과_준비정보로_대체하고_기한_조건을_보존한다() {
        var view = BusinessSummaryEmailView.from(BusinessSummaryEmailFixtures.parse("""
                {"analysis":{"summary":"기존 요약을 표시합니다.","businessSummary":{"purpose":"확인 필요","supportScale":"원문에서 확인하지 못했습니다."},
                  "applicationDeadline":"미확인","proposal":{"preparation":{"applicationDeadline":"온라인 10월 30일 18시; 원본 우편 11월 2일 도착분"}}},
                 "businessType":{"status":"NOT_APPLICABLE"}}
                """));
        assertThat(view.purpose()).isEqualTo("기존 요약을 표시합니다.");
        assertThat(view.deadline()).isEqualTo("온라인 10월 30일 18시, 원본 우편 11월 2일 도착분");
        assertThat(view.supportScale()).isEqualTo("확인 필요");
        assertThat(view.type()).isEqualTo("해당 없음");
        assertThat(view.score()).isNull();
    }

    @Test
    void 분석_대기와_미확인을_구별하고_없는_정보를_추정하지_않는다() {
        var pending = BusinessSummaryEmailView.from(BusinessSummaryEmailFixtures.parse("{\"analysisPending\":true}"));
        assertThat(pending.type()).isEqualTo("분석 중");
        assertThat(pending.organizations()).isEmpty();
        assertThat(pending.sourceOrganization()).isEqualTo("공공기관 확인 필요");
        assertThat(pending.deadline()).isEqualTo("확인 필요");
        var unknown = BusinessSummaryEmailView.from(BusinessSummaryEmailFixtures.parse(
                "{\"businessType\":{\"status\":\"CLASSIFIED\",\"code\":\"UNRECOGNIZED\"},\"changeType\":\"UNCHANGED_DOCUMENT\"}"));
        assertThat(unknown.type()).isEqualTo("확인 필요");
        assertThat(unknown.change()).isEqualTo("변경 없음");
    }

    @Test
    void 위험한_공고_원문_링크를_표시하지_않는다() {
        var view = BusinessSummaryEmailView.from(BusinessSummaryEmailFixtures.parse(
                "{\"originalUrl\":\"javascript:alert(1)\"}"));
        assertThat(view.originalUrl()).isNull();
    }

    @Test
    void 웹과_같이_이름과_역할과_근거가_확인된_기관만_표시한다() {
        var view = BusinessSummaryEmailView.from(BusinessSummaryEmailFixtures.parse("""
                {"responsibleOrganizations":[
                  {"name":"확인된 기관","role":"문의처","excerpt":"문의처: 확인된 기관"},
                  {"name":"미확인","role":"문의처","excerpt":"문의처"},
                  {"name":"역할 없는 기관","role":"확인 필요","excerpt":"기관 안내"},
                  {"name":"근거 없는 기관","role":"전담기관","excerpt":" "}]}
                """));
        assertThat(view.organizations()).extracting(BusinessSummaryEmailView.Organization::name)
                .containsExactly("확인된 기관");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "42백만원|4,200만 원", "3.5백만원|350만 원", "903백만 원|9억 300만 원",
        "42,000,000원|4,200만 원", "3,500천원|350만 원", "0.5억원|5,000만 원",
        "1.2345억원|1억 2,345만 원", "0.000001백만원|1원", "42.001백만원|4,200만 1,000원",
        "3.5천원|3,500원", "0백만원|0원", "-3.5백만원|-350만 원", "+42백만원|+4,200만 원",
        "1,234,567백만원|1조 2,345억 6,700만 원", "9,007,199,254,740,993원|9,007조 1,992억 5,474만 993원",
        "3.5~42백만원|350만 원~4,200만 원", "3.5백만원~42백만원 이하|350만 원~4,200만 원 이하"
    })
    void 웹과_같은_정확한_원화_표시를_사용한다(String input, String expected) {
        assertThat(BusinessSummaryEmailText.supportScale(input)).isEqualTo(expected);
        assertThat(BusinessSummaryEmailText.supportScale(expected)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1억 5천만원", "1억5,000만원", "42·50백만원", "42, 50백만원", "10~20~30백만원",
            "1,23백만원", "1.2.3백만원", "1e3백만원", "1e-3백만원", "0.0000001백만원", "0.5원",
            "0.0015~3백원", "1~1,23백만원", "42백만 달러", "지원액 42 (단위: 백만원)"})
    void 불명확한_금액과_복합_금액은_변경하지_않는다(String text) {
        assertThat(BusinessSummaryEmailText.supportScale(text)).isEqualTo(text);
    }
}
