package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class GeneralNoticeClassificationTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String DESIGNATION = "소관 재난관리책임기관을 다음과 같이 지정하여 고시합니다.";

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "재난관리책임기관 지정 및 고시|소관 재난관리책임기관을 다음과 같이 지정하여 고시합니다.",
        "전문기관 지정 고시|전문기관을 지정하고 이를 고시합니다.",
        "전담기관 변경 지정 고시|전담기관을 변경 지정하여 고시합니다.",
        "지정 해제 고시|전문기관의 지정을 해제하여 고시합니다.",
        "기관 지정 공고|아래 기관을 지정하였음을 알립니다."
    })
    void 완료된_지정은_기관명이나_연도에_의존하지_않고_일반공고로_확인한다(String title, String body) {
        var result = BusinessTypeResolver.resolve(title, body, List.of(), null, mapper);
        assertThat(result.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(result.code()).isNull();
    }

    @Test
    void 여러_줄의_고시문과_정상_첨부에서도_지정을_확인한다() {
        var result = BusinessTypeResolver.resolve("기관 지정 및 고시", "첨부 참조", List.of(
                file("고시문.hwpx", "소관 기관을 지정하여\n고시합니다.", AttachmentParseStatus.COMPLETED)), null, mapper);
        assertThat(result.status()).isEqualTo("NOT_APPLICABLE");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"REVIEW_REQUIRED", "BUSINESS_NOTICE", "PROPOSAL_REQUEST"})
    void 유형_빈값을_일반공고로_추정하지_않는다(String documentType) {
        var result = BusinessTypeResolver.resolve("안내", "자세한 사항은 별도 안내합니다.", List.of(),
                "{\"businessType\":null}", documentType, mapper);
        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void 기존_AI의_일반공지_판단을_사업유형_빈값과_구분해_사용한다() {
        var result = BusinessTypeResolver.resolve("기관 현황 안내", "현재 기관 현황을 알려 드립니다.",
                List.of(), "{\"businessType\":null}", "GENERAL_NOTICE", mapper);
        assertThat(result.status()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void 원문이_없으면_저장된_일반공지_분류만으로_확정하지_않는다() {
        assertThat(BusinessTypeResolver.resolve("안내", null, List.of(), null, "GENERAL_NOTICE", mapper).status())
                .isEqualTo("REVIEW_REQUIRED");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "기관 지정 고시|신청 기관을 모집합니다.",
        "기관 지정 고시|지정 신청을 받습니다.",
        "기관 지정 고시|희망 기관은 신청할 수 있습니다.",
        "기관 지정 고시|□ 모집대상: 수행기관",
        "기관 지정 고시|신청기간: 2026. 10. 20.부터 2026. 10. 30.까지",
        "기관 지정 고시|사업계획서를 제출하시기 바랍니다.",
        "수행기관 신규 모집|소관 기관을 지정하여 고시합니다.",
        "선정 결과 및 추가 모집|소관 기관을 지정하여 고시합니다.",
        "지정기관 공모|소관 기관을 지정하여 고시합니다."
    })
    void 실제_모집과_신청은_일반공지_분류나_고시_제목보다_우선한다(String title, String body) {
        var result = BusinessTypeResolver.resolve(title, body, List.of(), null, "GENERAL_NOTICE", mapper);
        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void 일반공지로_저장되어도_현재_지원내용이_확인되면_사업유형을_보존한다() {
        var result = BusinessTypeResolver.resolve("기관 안내", "□ 지원내용: 기업에 컨설팅을 제공합니다.",
                List.of(), null, "GENERAL_NOTICE", mapper);
        assertThat(result.status()).isEqualTo("CLASSIFIED");
        assertThat(result.code()).isEqualTo("ADVISORY");
    }

    @Test
    void 현재_원문으로_검증한_저장_사업유형도_일반공지_빈값으로_덮지_않는다() {
        String body = "기업에 설비 도입을 지원합니다.";
        String saved = mapper.writeValueAsString(Map.of("businessType", Map.of(
                "primaryType", "ADOPTION", "explanation", body, "details", List.of(),
                "sources", List.of(Map.of("origin", "NOTICE_BODY", "excerpt", body)))));
        var result = BusinessTypeResolver.resolve("사업 안내", body, List.of(), saved, "GENERAL_NOTICE", mapper);
        assertThat(result.code()).isEqualTo("ADOPTION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"지원사업 지정 고시", "연구기관 지정 공고"})
    void 고시라는_제목만_있고_원문_근거가_없으면_확인필요를_유지한다(String title) {
        assertThat(BusinessTypeResolver.resolve(title, "첨부 참조", List.of(), null, mapper).status())
                .isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void 실패_첨부와_작성예시_서식_과거_고시는_현재_지정의_근거가_아니다() {
        var result = BusinessTypeResolver.resolve("기관 지정 고시", "지난해 다음과 같이 지정하여 고시합니다.",
                List.of(file("고시문.pdf", DESIGNATION, AttachmentParseStatus.FAILED),
                        file("작성예시.pdf", DESIGNATION, AttachmentParseStatus.COMPLETED),
                        file("첨부.zip", "[파일: 작성예시.pdf]\n" + DESIGNATION
                                + "\n[파일: 공고.pdf]\n첨부 참조", AttachmentParseStatus.COMPLETED)),
                null, mapper);
        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(BusinessTypeResolver.resolve("기관 지정 고시", "별첨 1 신청서\n" + DESIGNATION,
                List.of(), null, mapper).status()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void 모집_부재를_명시한_고시와_의견제출_행정예고를_사업모집으로_오인하지_않는다() {
        assertThat(BusinessTypeResolver.resolve("기관 지정 고시",
                DESIGNATION + "\n신청서를 제출할 필요가 없으며 별도 모집하지 않습니다.", List.of(), null, mapper).status())
                .isEqualTo("NOT_APPLICABLE");
        assertThat(BusinessTypeResolver.resolve("행정예고",
                "의견제출 접수기간: 10월 20일까지", List.of(), null, mapper).status())
                .isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void 기관_자체_채용과_기업_채용지원사업을_구분한다() {
        assertThat(BusinessTypeResolver.resolve("기간제 직원 채용", "직원을 모집합니다.", List.of(), null, mapper).status())
                .isEqualTo("NOT_APPLICABLE");
        assertThat(BusinessTypeResolver.resolve("연구원 채용 공고",
                "□ 주요과업: 연구개발을 수행합니다.", List.of(), null, mapper).status())
                .isEqualTo("NOT_APPLICABLE");
        assertThat(BusinessTypeResolver.resolve("직원 채용지원 사업", "□ 지원내용: 기업의 채용지원을 제공합니다.",
                List.of(), null, "GENERAL_NOTICE", mapper).code()).isEqualTo("WORKFORCE");
    }

    @Test
    void 설명회_참석신청은_사업신청과_구분하되_실제_지원내용은_보존한다() {
        assertThat(BusinessTypeResolver.resolve("지원사업 설명회 개최",
                "참석 신청기간: 10월 20일까지\\n설명회 참석자를 모집합니다.", List.of(), null, mapper).status())
                .isEqualTo("NOT_APPLICABLE");
        assertThat(BusinessTypeResolver.resolve("설명회 개최 및 사업 참여기업 추가 모집",
                "□ 지원내용: 기업에 컨설팅을 제공합니다.", List.of(), null, "GENERAL_NOTICE", mapper).code())
                .isEqualTo("ADVISORY");
    }

    private DocumentAttachment file(String name, String text, AttachmentParseStatus status) {
        return DocumentAttachment.create(null, name, "https://example.com/file", "pdf", "application/pdf",
                100L, "a".repeat(64), text, status, null);
    }
}
