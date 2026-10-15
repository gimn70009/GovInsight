package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultRequest.ComparisonSummary;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class BusinessTypeResolverTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource({
        "RND,신기술 연구개발을 지원합니다.",
        "DEMONSTRATION,실제 현장에서 제품 실증을 지원합니다.",
        "COMMERCIALIZATION,시제품 제작비를 지원합니다.",
        "ADOPTION,생산 설비 도입을 지원합니다.",
        "CERTIFICATION,해외 규격 인증 취득비를 지원합니다.",
        "MARKET,전시회 참가와 해외진출을 지원합니다.",
        "FINANCE,운영 자금 융자를 지원합니다.",
        "WORKFORCE,신규 채용지원 및 직무교육을 제공합니다.",
        "ADVISORY,시장조사 및 컨설팅을 수행합니다.",
        "SPACE,기업 입주 공간을 제공합니다.",
        "CERTIFICATION,수출기업에 해외 인증 취득비를 지원합니다.",
        "WORKFORCE,생산설비 운영 인력의 직무교육을 지원합니다.",
        "RND,특허 보유 기업의 연구개발을 지원합니다."
    })
    void 현재_지원_활동으로_10개_유형을_분류한다(String code, String text) {
        var result = BusinessTypeResolver.resolve("기업 지원사업 모집", "□ 지원내용: " + text, List.of(), null, mapper);
        assertThat(result.code()).isEqualTo(code);
        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().getFirst().excerpt()).contains(text);
    }

    @Test
    void 복합사업의_대표_하나와_보조_설명을_현재_근거와_함께_반환한다() throws Exception {
        String body = "□ 사업목적: 중소기업의 CBAM 대응을 지원합니다.\n"
                + "□ 지원내용: 유형1 디지털 기반 자동화 MRV 인프라 구축 지원\n"
                + "유형2 제3자 검증 비용 지원\n□ 신청자격: 수출 기업";
        var result = BusinessTypeResolver.resolve("2026 중소기업 CBAM 대응 인프라구축 사업 4차 모집공고", body, List.of(), null, mapper);
        assertThat(result.code()).isEqualTo("ADOPTION");
        assertThat(result.details()).anyMatch(value -> value.contains("시험·인증"));
        assertThat(result.sources()).hasSize(2);
        var output = Path.of("build/business-type-checks");
        Files.createDirectories(output);
        Files.writeString(output.resolve("cbam.json"), mapper.writeValueAsString(result));
    }

    @ParameterizedTest
    @CsvSource({
        "인증기업 연구개발 실적 안내,□ 지원대상: 인증 보유 및 연구개발 실적 기업",
        "R&D 지원사업 모집,□ 지원규모: 사업비 1억원 지원",
        "예시 사업,□ 지원내용: 향후 실증 사업 연계 가능",
        "예시 사업,□ 지원내용: 연구개발 지원 불가",
        "예시 사업,□ 지원내용: 다른 사업의 설비 도입 지원 사례"
    })
    void 제목_자격_금액_연계_부정_문구만으로는_분류하지_않는다(String title, String body) {
        var result = BusinessTypeResolver.resolve(title, body, List.of(), null, mapper);
        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.code()).isNull();
    }

    @Test
    void 평탄한_본문의_자격과_지원_내용을_구분하고_보조금을_금융으로_분류하지_않는다() {
        var result = BusinessTypeResolver.resolve(null,
                "□ 지원대상: 수출·인증 기업 □ 지원내용: 설비 도입 보조금 지원 □ 신청자격: 연구개발 실적 보유",
                List.of(), null, mapper);
        assertThat(result.code()).isEqualTo("ADOPTION");
        assertThat(result.details()).isEmpty();
    }

    @Test
    void 신청서_예시와_실패_첨부는_분류하지_않는다() {
        var files = List.of(file("신청서.pdf", "□ 지원내용: 연구개발 지원", AttachmentParseStatus.COMPLETED),
                file("공고문.pdf", "□ 지원내용: 연구개발 지원", AttachmentParseStatus.FAILED));
        assertThat(BusinessTypeResolver.resolve("모집공고", "별첨 1 신청서\n□ 지원내용: 연구개발 지원", files, null, mapper).code()).isNull();
    }

    @Test
    void 일반_결과_공고는_지원_유형을_붙이지_않는다() {
        assertThat(BusinessTypeResolver.resolve("연구개발 선정 결과 공고", "□ 지원내용: 연구개발 지원", List.of(), null, mapper).status()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void 저장된_분류는_현재_원문과_정확한_파일명을_검증한다() {
        String excerpt = "유형1은 생산 시스템을 구축하고 유형2는 외부기관 검증을 지원합니다.";
        String saved = mapper.writeValueAsString(Map.of("businessType", Map.of("primaryType", "ADOPTION",
                "explanation", "배출량을 관리하는 시스템 구축을 지원합니다.", "details", List.of("유형2는 외부 검증 비용을 지원합니다."),
                "sources", List.of(Map.of("origin", "ATTACHMENT", "attachmentName", "공고.pdf", "excerpt", excerpt)))));
        var good = file("공고.pdf", excerpt, AttachmentParseStatus.COMPLETED);
        var result = BusinessTypeResolver.resolve("사업 모집", null, List.of(good), saved, mapper);
        assertThat(result.code()).isEqualTo("ADOPTION");
        assertThat(result.explanation()).contains("배출량");
        assertThat(BusinessTypeResolver.resolve("사업 모집", null, List.of(good, good), saved, mapper).code()).isNull();
        assertThat(BusinessTypeResolver.resolve("사업 모집", null, List.of(file("다른공고.pdf", excerpt, AttachmentParseStatus.COMPLETED)), saved, mapper).code()).isNull();
        assertThat(BusinessTypeResolver.resolve("사업 모집", "새 원문입니다.", List.of(), saved, mapper).code()).isNull();
        assertThat(mapper.readValue(saved, ComparisonSummary.class).businessType().primaryType()).isEqualTo(BusinessType.ADOPTION);
        assertThat(mapper.writeValueAsString(mapper.readValue(saved, ComparisonSummary.class))).contains("\"primaryType\":\"ADOPTION\"");
    }

    @ParameterizedTest
    @CsvSource({"clean-structured.txt,ADOPTION", "certification-hwpx.txt,CERTIFICATION", "certification-pdf.txt,CERTIFICATION"})
    void 실제_공고_원문을_유형별_하드코딩_없이_분류한다(String name, String code) throws Exception {
        String text = Files.readString(Path.of("src/test/resources/participation-conditions", name));
        var result = BusinessTypeResolver.resolve("참여기업 모집 공고", null, List.of(file("공고.pdf", text, AttachmentParseStatus.COMPLETED)), null, mapper);
        assertThat(result.code()).isEqualTo(code);
        assertThat(result.sources()).allSatisfy(source -> assertThat(text).contains(source.excerpt()));
    }

    @Test
    void 압축파일의_작성예시를_제외하고_실제_공고를_분류한다() {
        String zip = "[파일: 신청서_예시.pdf]\n□ 지원내용: 연구개발 지원\n"
                + "[파일: 모집공고.pdf]\n□ 지원내용: 설비 도입 지원";
        var result = BusinessTypeResolver.resolve("기업 모집", null,
                List.of(file("첨부.zip", zip, AttachmentParseStatus.COMPLETED)), null, mapper);
        assertThat(result.code()).isEqualTo("ADOPTION");
        assertThat(result.details()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"cbam-structured.txt"})
    void 기존_CBAM_공고도_사업_내용으로_분류한다(String name) throws Exception {
        String text = Files.readString(Path.of("src/test/resources/submission-documents", name));
        var result = BusinessTypeResolver.resolve("2026 중소기업 CBAM 대응 인프라구축 사업 4차 모집공고", null,
                List.of(file("공고.pdf", text, AttachmentParseStatus.COMPLETED)), null, mapper);
        assertThat(result.code()).isEqualTo("ADOPTION");
    }

    @Test
    void 신청서류_목록만_있는_자료에는_제목으로_유형을_붙이지_않는다() throws Exception {
        String text = Files.readString(Path.of("src/test/resources/submission-documents/cbam.txt"));
        var result = BusinessTypeResolver.resolve("2026 중소기업 CBAM 대응 인프라구축 사업", text, List.of(), null, mapper);
        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
    }

    private DocumentAttachment file(String name, String text, AttachmentParseStatus status) {
        return DocumentAttachment.create(null, name, "https://example.com/file", "pdf", "application/pdf", 100L, "a".repeat(64), text, status, null);
    }
}
