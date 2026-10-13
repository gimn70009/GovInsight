package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ResponsibleOrganization;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResponsibleOrganizationExtractorTest {
    @Test
    void 명시된_기관과_역할을_출처와_함께_반환한다() {
        var result = ResponsibleOrganizationExtractor.extract("□ (주관기관) 한국산업진흥원\n운영기관 : 한국기술재단", List.of());
        assertThat(result).extracting(ResponsibleOrganization::name).containsExactly("한국산업진흥원", "한국기술재단");
        assertThat(result).extracting(ResponsibleOrganization::role).containsExactly("주관기관", "운영기관");
        assertThat(result.getFirst().sourceType()).isEqualTo("NOTICE_BODY");
        assertThat(result.getFirst().excerpt()).isEqualTo("□ (주관기관) 한국산업진흥원");
    }

    @Test
    void CBAM_공고의_분리된_표_셀에서_총괄기관과_시행기관을_구분한다() {
        String text = """
                구 분
                역 할
                총괄기관
                (중소벤처기업부)
                ⦁정책 수립 및 사업 기획 총괄
                시행기관
                (중소벤처기업진흥공단)
                ⦁총괄 관리(기획, 운영, 관리, 정산 등) 및 성과 분석
                수요기업
                (중소기업)
                수행기관
                (전문기업)
                □ (문의처) 중소벤처기업진흥공단 탄소중립지원센터 055-751-9732
                """;
        var result = ResponsibleOrganizationExtractor.extract("", List.of(attachment("CBAM_공고.hwpx", text)));
        assertThat(result).extracting(ResponsibleOrganization::name).containsExactly("중소벤처기업부", "중소벤처기업진흥공단");
        assertThat(result).extracting(ResponsibleOrganization::role).containsExactly("총괄기관", "시행기관");
        assertThat(result.getLast().attachmentName()).isEqualTo("CBAM_공고.hwpx");
        assertThat(result.getLast().excerpt()).isEqualTo("시행기관\n(중소벤처기업진흥공단)");
    }

    @Test
    void 포상_공고의_문의_기관을_주관기관으로_바꾸지_않는다() {
        String text = """
                서식1 포상 신청서
                주관기관 : 신청기업진흥원
                ◈ (문의처) 대·중소기업·농어업협력재단 02-368-8929, 8920
                """;
        var result = ResponsibleOrganizationExtractor.extract(null, List.of(attachment("포상_공고.hwpx", text)));
        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("대·중소기업·농어업협력재단");
            assertThat(item.role()).isEqualTo("문의처");
            assertThat(item.sourceType()).isEqualTo("ATTACHMENT");
        });
    }

    @Test
    void 기관_역할_없는_게시기관과_지원대상_참고문장을_추정하지_않는다() {
        var result = ResponsibleOrganizationExtractor.extract("""
                중소벤처기업부 공고
                주관연구개발기관 : 한국연구원
                주관기관 모집공고
                신청자격 : 한국진흥원 지원사업 참여기업
                전년도 사업 주관기관 : 한국진흥원
                주관기관 : 전년도 한국진흥원
                주관기관 : OO진흥원
                주관기관 : 전문기업
                주관기관 : 추후 선정
                주관기관 : 해당 기관
                주관기관 : 한국진흥원(예시)
                운영기관 : 한국연구원(선정 예정)
                문의처 : 02-000-0000
                """, List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void 작성예시와_별도_양식의_기관을_사업_담당기관으로_노출하지_않는다() {
        var result = ResponsibleOrganizationExtractor.extract("작성 예시\n운영기관: 예제진흥원", List.of(
                attachment("신청서.hwpx", "주관기관: 신청인연구원"),
                attachment("참고자료.pdf", "전담기관: 타사업진흥원"),
                attachment("FAQ.pdf", "주관기관: 예제연구원")));
        assertThat(result).isEmpty();
    }

    @Test
    void 원문과_첨부의_동일_근거는_중복하지_않고_공동_기관과_역할은_보존한다() {
        var result = ResponsibleOrganizationExtractor.extract("주관기관 : 한국진흥원, 한국재단", List.of(
                attachment("공고.pdf", "주관기관 : 한국진흥원\n문의처 : 한국진흥원 담당팀"),
                attachment("공고.hwpx", "주관기관 : 한국재단\n운영기관 : 한국진흥원")));
        assertThat(result).extracting(ResponsibleOrganization::name).containsExactly("한국진흥원", "한국재단", "한국진흥원");
        assertThat(result).extracting(ResponsibleOrganization::role).containsExactly("주관기관", "주관기관", "운영기관");
    }

    @Test
    void 괄호와_표_구분자_띄어진_역할명도_읽는다() {
        for (String text : List.of("주관기관(한국진흥원)", "[주관기관] 한국진흥원", "| 주 관 기 관 | 한국진흥원 | 담당팀", "주관기관 한국진흥원")) {
            assertThat(ResponsibleOrganizationExtractor.extract(text, List.of()))
                    .extracting(ResponsibleOrganization::name).containsExactly("한국진흥원");
        }
    }

    @Test
    void 비어있거나_파싱실패한_첨부에서는_추출하지_않는다() {
        var failed = DocumentAttachment.create(null, "공고.pdf", "https://example.org/file", "pdf",
                "application/pdf", 1L, "hash", "주관기관: 한국진흥원", AttachmentParseStatus.FAILED, "파싱 실패");
        assertThat(ResponsibleOrganizationExtractor.extract(null, List.of(failed))).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("", List.of(attachment("공고.hwpx", null)))).isEmpty();
    }

    private DocumentAttachment attachment(String name, String text) {
        return DocumentAttachment.create(null, name, "https://example.org/file", "hwpx", "application/zip", 1L,
                "hash", text, AttachmentParseStatus.COMPLETED, null);
    }
}
