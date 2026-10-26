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

    @Test
    void 실제_스마트제조_포상_표의_병합_역할명을_기관명에_붙이지_않는다() throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/organizations/smart-award-structured.txt")) {
            text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var result = ResponsibleOrganizationExtractor.extract(null, List.of(attachment("포상_공고.hwpx", text)));
        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("중소벤처기업부");
            assertThat(item.role()).isEqualTo("주관기관");
            assertThat(item.excerpt()).isEqualTo("주관기관\t\t중소벤처기업부");
            assertThat(text).contains(item.excerpt());
        });
    }

    @Test
    void 표의_반복_셀은_줄이고_서로_다른_기관과_역할은_구별한다() {
        String text = "[표]\n주관기관\t주관기관\t한국진흥원\t한국진흥원\t운영기관\t한국재단\n"
                + "문의처\t문의처\t한국진흥원\t한국진흥원\t전담기관\t한국연구원\n[/표]";
        var result = ResponsibleOrganizationExtractor.extract(text, List.of());
        assertThat(result).extracting(ResponsibleOrganization::name).containsExactly("한국진흥원", "한국재단", "한국연구원");
        assertThat(result).extracting(ResponsibleOrganization::role).containsExactly("주관기관", "운영기관", "전담기관");
        assertThat(result).allSatisfy(item -> assertThat(text).contains(item.excerpt()));
    }

    @Test
    void 위아래_기관_셀은_같은_열끼리만_연결한다() {
        String text = "[표]\n주관기관\t운영기관\n한국진흥원\t한국재단\n[/표]";
        var result = ResponsibleOrganizationExtractor.extract(text, List.of());
        assertThat(result).extracting(ResponsibleOrganization::name).containsExactly("한국진흥원", "한국재단");
        assertThat(result).extracting(ResponsibleOrganization::role).containsExactly("주관기관", "운영기관");
        assertThat(result).allSatisfy(item -> assertThat(text).contains(item.excerpt()));
        assertThat(ResponsibleOrganizationExtractor.extract("[표]\n주관기관\t설명\n\t한국재단\n[/표]", List.of())).isEmpty();
    }

    @Test
    void 잘린_표와_작성예시_표는_기관을_확정하지_않는다() {
        String table = "[표]\n주관기관\t한국진흥원\n운영기관\t한국재단\n[/표]";
        assertThat(ResponsibleOrganizationExtractor.extract(table.replace("[/표]", ""), List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("작성 예시\n" + table, List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("서식1 신청서\n" + table, List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("[표]\n주관기관\t\n[/표]\n한국진흥원", List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("주관기관 주관기관 한국진흥원", List.of())).isEmpty();
    }

    @Test
    void 실제_CBAM_전체_구조화_본문에서도_총괄기관과_시행기관을_보존한다() throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/submission-documents/cbam-structured.txt")) {
            text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var result = ResponsibleOrganizationExtractor.extract(null, List.of(attachment("CBAM_공고.hwpx", text)));
        assertThat(result).extracting(ResponsibleOrganization::name).containsExactly("중소벤처기업부", "중소벤처기업진흥공단");
        assertThat(result).extracting(ResponsibleOrganization::role).containsExactly("총괄기관", "시행기관");
        assertThat(result).allSatisfy(item -> assertThat(text).contains(item.excerpt()));
    }


    @Test
    void 참여인력과_일반적인_기관종류를_담당기관명으로_읽지_않는다() {
        for (String value : List.of("참여연구원", "참여 연구원", "책임연구원", "소속 연구원",
                "기업부설연구소", "기업 부설 연구소", "국내 연구기관", "대학", "대학교", "연구소", "사무국")) {
            assertThat(ResponsibleOrganizationExtractor.extract("주관기관 : " + value, List.of()))
                    .as(value).isEmpty();
            assertThat(ResponsibleOrganizationExtractor.extract(
                    "[표]\n주관기관\t" + value + "\n[/표]", List.of())).as(value).isEmpty();
        }
    }

    @Test
    void 공동연구기관_서류명을_주관_역할과_기관명으로_분리하지_않는다() {
        for (String text : List.of(
                "주관 및 공동연구개발기관 기업부설연구소 인정서",
                "주관 및\n공동연구개발기관 기업부설연구소 인정서",
                "주관기관 : 한국연구원 재직증명서",
                "[표]\n번호\t서류 유형\t파일 형식\n7\t주관 및 공동연구개발기관 기업부설연구소 인정서\tPDF\n[/표]")) {
            assertThat(ResponsibleOrganizationExtractor.extract(text, List.of())).as(text).isEmpty();
        }
    }

    @Test
    void 연결어는_완전한_기관명_사이에_있을_때만_목록으로_읽는다() {
        assertThat(ResponsibleOrganizationExtractor.extract(
                "주관기관: 한국연구원 및 한국기술재단", List.of()))
                .extracting(ResponsibleOrganization::name).containsExactly("한국연구원", "한국기술재단");
        assertThat(ResponsibleOrganizationExtractor.extract("주관 및 한국연구원", List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("주관기관: 및 한국연구원", List.of())).isEmpty();
    }

    @Test
    void ZIP_내부_서식은_건너뛰고_다음_공고의_기관은_독립적으로_읽는다() {
        String text = """
                [파일: 참여확인서.hwp]
                주관기관 : 신청인연구원
                문의처 : 신청인연구소
                [파일: 공고문.hwp]
                전담기관 : 한국산업기술진흥원
                [파일: 사업계획서.hwp]
                작성 예시
                주관기관 : 예제진흥원
                [파일: 추가 공고문.hwp]
                (접수처) 유레카 사무국 https://example.org
                """;
        var result = ResponsibleOrganizationExtractor.extract(null, List.of(attachment("첨부 일체.zip", text)));
        assertThat(result).extracting(ResponsibleOrganization::name)
                .containsExactly("한국산업기술진흥원", "유레카 사무국");
        assertThat(result).extracting(ResponsibleOrganization::role).containsExactly("전담기관", "접수처");
        assertThat(result).allSatisfy(item -> assertThat(text).contains(item.excerpt()));
    }

    @Test
    void 역할과_기관을_다른_ZIP_문서에서_연결하지_않는다() {
        String text = """
                [파일: 앞 공고문.hwp]
                주관기관
                [파일: 뒤 공고문.hwp]
                한국연구원
                """;
        assertThat(ResponsibleOrganizationExtractor.extract(null, List.of(attachment("공고.zip", text)))).isEmpty();
    }

    @Test
    void 실제_일본_공고의_서류와_인력_표는_제외하고_접수기관을_보존한다() throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/organizations/japan-globalstars.txt")) {
            text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var result = ResponsibleOrganizationExtractor.extract(null, List.of(attachment("첨부 일체.zip", text)));
        assertThat(result).extracting(ResponsibleOrganization::name)
                .containsExactly("유레카 사무국", "한국산업기술진흥원");
        assertThat(result).allSatisfy(item -> {
            assertThat(item.role()).isEqualTo("접수처");
            assertThat(item.sourceType()).isEqualTo("ATTACHMENT");
            assertThat(text).contains(item.excerpt());
        });
    }

    private DocumentAttachment attachment(String name, String text) {
        return DocumentAttachment.create(null, name, "https://example.org/file", "hwpx", "application/zip", 1L,
                "hash", text, AttachmentParseStatus.COMPLETED, null);
    }
}
