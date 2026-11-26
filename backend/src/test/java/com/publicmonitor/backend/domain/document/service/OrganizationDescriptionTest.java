package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ResponsibleOrganization;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrganizationDescriptionTest {
    @Test
    void 창업도시_실제_표의_운영예산_지원은_제외하고_운영기관_근거를_유지한다() throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/organizations/startup-city.txt")) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var file = DocumentAttachment.create(null, "창업도시_모집_공고.hwpx", "https://example.org/notice",
                "hwpx", "application/zip", 1L, "hash", text, AttachmentParseStatus.COMPLETED, null);
        var result = ResponsibleOrganizationExtractor.extract(null, List.of(file));
        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("창조경제혁신센터");
            assertThat(item.role()).isEqualTo("운영기관");
            assertThat(item.attachmentName()).isEqualTo("창업도시_모집_공고.hwpx");
            assertThat(item.excerpt()).isEqualTo("·운영기관(창조경제혁신센터) 내 전담조직 설치 및 전담인력 확보");
            assertThat(text).contains(item.excerpt());
        });
    }

    @Test
    void 기관_접미사와_끝글자가_같은_업무_표현을_기관명으로_읽지_않는다() {
        for (String description : List.of("프로그램 운영예산 지원", "창업기업 사업화지원", "운영예산 지 원",
                "온라인 사업 신청", "지원사업 실 시", "운영계획 제시", "사업비 교부", "보조금 지급 여부",
                "자료 송부", "서류 첨부", "교육자료 배부", "부담금 납부", "창업기업 성장 유도", "사업 운영 지도",
                "창업 지원 제도", "사업 투입 인원", "지방 재원", "기관 회원", "프로그램 후원", "사업 수행 시", "성과 보고시")) {
            for (String role : List.of("운영기관", "전담기관", "주관기관", "문의처")) {
                for (String text : List.of(role + " " + description, role + ": " + description,
                        role + "\n" + description,
                        "[표]\n구분\t내용\n지원내용\t·" + role + " " + description + "\n[/표]",
                        "[표]\n" + role + "\t" + description + "\n[/표]")) {
                    assertThat(ResponsibleOrganizationExtractor.extract(text, List.of())).as(text).isEmpty();
                }
            }
        }
    }

    @Test
    void 업무_단어가_포함되어도_완전한_기관명과_행정기관은_보존한다() {
        for (String name : List.of("창업지원센터", "한국사회적기업진흥원", "한국보건의료정보원", "한국교육학술정보원",
                "서울의료원", "한국장학재단", "한국청소년상담복지개발원", "중소벤처기업부", "기획재정부",
                "서울특별시", "강원특별자치도", "경기도", "조달청", "농촌진흥청", "국가유산청")) {
            for (String text : List.of("운영기관: " + name, "[표]\n운영기관\t" + name + "\n[/표]")) {
                assertThat(ResponsibleOrganizationExtractor.extract(text, List.of()))
                        .as(text).extracting(ResponsibleOrganization::name).containsExactly(name);
            }
        }
    }

    @Test
    void 정상_기관_뒤_업무_설명은_추가_기관으로_노출하지_않는다() {
        for (String description : List.of("프로그램 운영예산 지원", "사업비 교부", "온라인 사업 신청")) {
            String text = "운영기관: 창조경제혁신센터 및 " + description;
            assertThat(ResponsibleOrganizationExtractor.extract(text, List.of()))
                    .extracting(ResponsibleOrganization::name).containsExactly("창조경제혁신센터");
        }
    }
}
