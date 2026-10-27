package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ResponsibleOrganization;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

class OrganizationExpressionTest {
    @TestFactory
    Stream<DynamicTest> 진단에서_고정한_215개_표현의_기관과_역할과_근거를_검증한다() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        try (var stream = getClass().getResourceAsStream("/organizations/expression-cases.json")) {
            var cases = JsonMapper.builder().build().readTree(stream.readAllBytes());
            for (var c : cases) {
                tests.add(DynamicTest.dynamicTest(c.get("id").asString() + " " + c.get("label").asString(), () -> {
                    String body = c.get("body").asString();
                    var files = new ArrayList<DocumentAttachment>();
                    for (var f : c.get("attachments")) files.add(DocumentAttachment.create(null, f.get("name").asString(),
                            "https://example.org/notice", "hwpx", null, null, null, f.get("text").asString(),
                            AttachmentParseStatus.valueOf(f.get("status").asString()), null));
                    var expected = new ArrayList<Map<String, String>>();
                    for (var item : c.get("expected")) expected.add(Map.of("name", item.get("name").asString(), "role", item.get("role").asString()));
                    var actual = ResponsibleOrganizationExtractor.extract(body, files);
                    assertThat(actual.stream().map(item -> Map.of("name", item.name(), "role", item.role())).toList())
                            .as(body).isEqualTo(expected);
                    for (var item : actual) {
                        if (item.sourceType().equals("NOTICE_BODY")) {
                            assertThat(item.attachmentName()).isNull();
                            assertThat(body).contains(item.excerpt());
                        } else {
                            assertThat(item.sourceType()).isEqualTo("ATTACHMENT");
                            assertThat(files).anySatisfy(file -> {
                                assertThat(file.getFileName()).isEqualTo(item.attachmentName());
                                assertThat(file.getExtractedText()).contains(item.excerpt());
                            });
                        }
                    }
                }));
            }
        }
        return tests.stream();
    }

    @Test
    void 실제_기술나눔_표의_업무설명을_서식_제목으로_판단하지_않는다() throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/organizations/energy-sharing-contact.txt")) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var result = ResponsibleOrganizationExtractor.extract(text, List.of());
        assertThat(result).extracting(ResponsibleOrganization::name).containsExactly("산업통상부", "한국산업기술진흥원");
        assertThat(result).extracting(ResponsibleOrganization::role).containsExactly("시행부처", "전문기관");
        assertThat(result).allSatisfy(item -> assertThat(text).contains(item.excerpt()));
    }

    @Test
    void 새로_허용한_표기에서도_설명과_부정과_서류를_기관으로_만들지_않는다() {
        for (String value : List.of("PDF", "HWP", "RFP", "사업비 집행 및 한국진흥원 지원", "프로그램 예산 한국지원센터",
                "필요도구", "온라인 접수", "한국연구원 인정서", "한국연구원이 아님", "한국연구원(담당 제외)")) {
            for (String label : List.of("사업 주관기관: ", "주관기관은 ", "주 관 기 관 - ")) {
                assertThat(ResponsibleOrganizationExtractor.extract(label + value, List.of())).as(label + value).isEmpty();
            }
        }
    }

    @Test
    void 근거는_빈줄과_공백과_줄끝을_포함해_원문을_그대로_사용한다() {
        String text = "  주관기관:\r\n\r\n  □ 한국진흥원  \r\n문의처:\n□ 한국재단";
        var result = ResponsibleOrganizationExtractor.extract(text, List.of());
        assertThat(result).extracting(ResponsibleOrganization::name).containsExactly("한국진흥원", "한국재단");
        assertThat(result).allSatisfy(item -> assertThat(text).contains(item.excerpt()));
    }

    @Test
    void 설명_문구와_이름_목록을_역할별로_독립적으로_판단한다() {
        var result = ResponsibleOrganizationExtractor.extract("주관기관: 한국연구원 / 운영기관: 창업지원센터 / 문의처: 한국재단", List.of());
        assertThat(result).extracting(ResponsibleOrganization::name).containsExactly("한국연구원", "창업지원센터", "한국재단");
        assertThat(ResponsibleOrganizationExtractor.extract("전년도 사업 주관기관: 한국연구원 / 운영기관: 한국재단", List.of())).isEmpty();
    }
    @Test
    void 단일_원_접미사를_제거해도_기관_종류와_법인명은_보존한다() {
        for (String name : List.of("한국중소벤처기업유통원", "한국소비자원", "한국부동산원", "한국저작권보호원",
                "한국승강기안전공단", "한국제품안전관리원", "한국교육과정평가원", "국립산림과학원",
                "국립수목원", "국가기록원", "금융감독원", "국가기술표준원", "(주)가나다", "주식회사 가나다")) {
            var result = ResponsibleOrganizationExtractor.extract("전담기관: " + name, List.of());
            assertThat(result).as(name).extracting(ResponsibleOrganization::name).containsExactly(name);
        }
    }

    @Test
    void 한글_일반명사와_표제는_행정기관으로_오인하지_않는다() {
        for (String value : List.of("연구기구", "정부기구", "자료요구", "필요도구", "자치구", "행정구", "법정구")) {
            assertThat(ResponsibleOrganizationExtractor.extract("운영기관: " + value, List.of())).as(value).isEmpty();
        }
    }

    @Test
    void 단일_기관명에_포함된_가운데점과_역할이_다른_기관을_보존한다() {
        var result = ResponsibleOrganizationExtractor.extract(
                "주관기관: 대·중소기업·농어업협력재단·한국연구원 / 문의처: 한국재단", List.of());
        assertThat(result).extracting(ResponsibleOrganization::name)
                .containsExactly("대·중소기업·농어업협력재단", "한국연구원", "한국재단");
    }

    @Test
    void 미확정_기관으로_변경하는_문장이나_다른_파일의_이름을_연결하지_않는다() {
        assertThat(ResponsibleOrganizationExtractor.extract("주관기관: 한국연구원에서 한국재단으로 변경 예정", List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("주관기관:\n[표]\n설명\t한국연구원\n[/표]", List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("주관기관: 한국산업\n운영기관: 한국재단", List.of()))
                .extracting(ResponsibleOrganization::name).containsExactly("한국재단");
    }

    @Test
    void 이름이_이어져도_기관_수와_입력_상한을_넘지_않는다() {
        String list = "주관기관: 한국연구원\n" + "한국재단\n".repeat(1000);
        assertThat(ResponsibleOrganizationExtractor.extract(list, List.of()))
                .extracting(ResponsibleOrganization::name).containsExactly("한국연구원", "한국재단");
        assertThat(ResponsibleOrganizationExtractor.extract("x".repeat(200_000) + "\n주관기관: 한국재단", List.of())).isEmpty();
    }

    @Test
    void 여러_역할의_구분자는_기관_목록의_구분자와_별도로_읽는다() {
        for (String separator : List.of(", ", "/", " / ", "; ", " | ")) {
            var result = ResponsibleOrganizationExtractor.extract(
                    "주관기관(한국연구원)" + separator + "(운영기관) 한국재단", List.of());
            assertThat(result).as(separator).extracting(ResponsibleOrganization::name).containsExactly("한국연구원", "한국재단");
            assertThat(result).extracting(ResponsibleOrganization::role).containsExactly("주관기관", "운영기관");
        }
    }

    @Test
    void 표의_업무설명은_서식_영역을_만들지_않고_실제_서식_제목은_보호한다() {
        for (String description : List.of("신청서류 작성 안내", "신청서 작성 안내", "사업계획서 검토 및 사업비 집행", "신청서 작성 예시 안내", "전년도 실적 검토")) {
            var result = ResponsibleOrganizationExtractor.extract(
                    "[표]\n전문기관\t한국진흥원\t" + description + "\n[/표]\n운영기관: 한국재단", List.of());
            assertThat(result).as(description).extracting(ResponsibleOrganization::name).containsExactly("한국진흥원", "한국재단");
        }
        assertThat(ResponsibleOrganizationExtractor.extract("[표]\n작성 예시\t작성 예시\n주관기관\t한국연구원\n[/표]", List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("[표]\n전년도\t주관기관\t한국연구원\n[/표]", List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("신청서\n주관기관: 한국연구원", List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("[표]\n서식1\t서식1\n주관기관\t한국연구원\n[/표]", List.of())).isEmpty();
    }

    @Test
    void 창업도시의_명시된_총괄과_전문기관을_원문_역할로_읽는다() {
        String text = "◦ 전문기관(창업진흥원)에서 배정한 현장평가일은 변경 불가\n"
                + "◦ 총괄기관(중소벤처기업부), 전문기관(창업진흥원)의 점검*(수시·최종 등) 결과에 따라, 협약 중단, 정부지원사업비 환수 등 제재 가능";
        var result = ResponsibleOrganizationExtractor.extract(text, List.of());
        assertThat(result).extracting(ResponsibleOrganization::name).containsExactly("창업진흥원", "중소벤처기업부");
        assertThat(result).extracting(ResponsibleOrganization::role).containsExactly("전문기관", "총괄기관");
        assertThat(result).allSatisfy(item -> assertThat(text).contains(item.excerpt()));
    }

    @Test
    void 실제_전통시장_공고의_복합_주최주관을_각_기관_묶음과_연결한다() {
        String text = "◦ (주최/주관) 중소벤처기업부/소상공인시장진흥공단, 전국상인연합회";
        var result = ResponsibleOrganizationExtractor.extract(text, List.of());
        assertThat(result).extracting(ResponsibleOrganization::name)
                .containsExactly("중소벤처기업부", "소상공인시장진흥공단", "전국상인연합회");
        assertThat(result).extracting(ResponsibleOrganization::role).containsExactly("주최", "주관", "주관");
        assertThat(result).allSatisfy(item -> assertThat(item.excerpt()).isEqualTo(text));
        assertThat(ResponsibleOrganizationExtractor.extract("주최/주관: 한국연구원", List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("(주최/주관) 한국연구원/", List.of())).isEmpty();
        assertThat(ResponsibleOrganizationExtractor.extract("주최/주관: 한국연구원/한국재단/한국진흥원", List.of())).isEmpty();
    }

    @Test
    void 다른_역할의_미확정_상태를_현재_역할에_옮기지_않는다() {
        var result = ResponsibleOrganizationExtractor.extract("주관기관: 미정 / 운영기관: 한국재단", List.of());
        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("한국재단");
            assertThat(item.role()).isEqualTo("운영기관");
        });
    }

}
