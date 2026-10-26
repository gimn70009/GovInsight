package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import com.publicmonitor.backend.domain.document.entity.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ParticipationDisplayTextTest {
    private ParticipationRule rule(String text) {
        return new ParticipationRule(text.contains("자부담") ? "FUNDING" : "BASIC", "참여 자격", text, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(new RequirementSource("NOTICE_BODY", null, "신청자격", null, text)));
    }
    private List<ParticipationCriterionEntry> entries(List<ParticipationCategory> groups, String code) {
        return groups.stream().flatMap(group -> group.rows().stream()).filter(row -> row.code().equals(code)).flatMap(row -> row.entries().stream()).toList();
    }
    private String text(List<ParticipationCategory> groups, String code) {
        return String.join("\n", entries(groups, code).stream().map(entry -> entry.rule().text()).toList());
    }
    @Test void 항목번호_참조표기_어색한어미를_정리하고_실제수치를_보존한다() {
        var groups = ParticipationCategoryProjector.project(List.of(
                rule("① 한국표준산업분류 상 중분류 기준 '제조업(업종코드 : C10~C34)'을 주된 사업으로 신청 영위중이어야 함([참고1],[참고2] 참조) 조건."),
                rule("2. 업종별 상시 근로자 수 등이 대통령령으로 정하는 기준에 해당할 것 나."),
                rule("(1) 상시 근로자 수가 10명 미만일 것.")), false);
        assertThat(text(groups, "SECTOR")).contains("C10~C34", "영위하고 있어야 합니다.").doesNotContain("[참고", "①", "신청 영위", "조건.");
        assertThat(text(groups, "HEADCOUNT")).contains("상시근로자 수가 10명 미만이어야 합니다.",
                "업종별 상시근로자 수가 대통령령으로 정하는 기준에 해당해야 합니다.").doesNotContain("나.");
    }
    @Test void 같은_지원방식의_공백과번호차이는_하나로_합치고_두근거를_유지한다() {
        var groups = ParticipationCategoryProjector.project(List.of(rule("라. 지원방식 : 사업비 매칭지원(국비 70% 이내, 자부담 30% 이상)."),
                rule("라. 지원방식 :사업비 매칭지원(국비 70% 이내, 자부담 30% 이상).")), false);
        assertThat(entries(groups, "MATCHING")).singleElement().satisfies(entry -> {
            assertThat(entry.rule().text()).isEqualTo("지원방식은 사업비 매칭지원이며, 국비는 70% 이내, 자부담은 30% 이상입니다.");
            assertThat(entry.rule().sources()).hasSize(2);
        });
    }
    @Test void 고위험업종_소제목과_설명을_독립된_문장으로_표시한다() {
        String original = "* (고위험업종 유형) 고위험업종 산재보험 가입 코드와 일치 필요(2p 지원내용 참조)";
        var groups = ParticipationCategoryProjector.project(List.of(rule(original)), false);
        assertThat(text(groups, "SECTOR")).contains("고위험업종 유형으로 신청하려면 산재보험 가입 코드가 고위험업종 코드와 일치해야 합니다.")
                .doesNotContain("2p", "참조", "*");
        assertThat(entries(groups, "SECTOR").getFirst().rule().sources().getFirst().excerpt()).isEqualTo(original);
    }
    @Test void 법령_인용을_앞조건과_분리하고_정의전문을_붙이지_않는다() {
        String original = "나. 대통령령으로 정하는 업종을 주된 사업으로 영위할 것 < 소상공인기본법 > 제2조(정의) ① 이 법에서 “소상공인”이란 「중소기업기본법」 제2조제2항에 따른 소기업 중 다음 각 호의 요건을 모두 갖춘 자를 말한다.";
        var groups = ParticipationCategoryProjector.project(List.of(rule(original)), false);
        assertThat(text(groups, "SECTOR")).contains("업종을 주된 사업으로 영위해야 합니다.").doesNotContain("<", "①", "이 법에서");
        assertThat(text(groups, "COMPANY_SIZE")).contains("소상공인기본법상 소상공인에 해당해야 합니다.").doesNotContain("각 호", "이 법에서", "<");
    }
    @Test void 복원불가_표와_중간이_손상된_숫자는_조건문으로_노출하지_않는다() {
        String damaged = "바. 지원내용 : 안전환경(일반), 안전환경(고위험업종) 중 1개 항목 선택지원 지원한도 지원유형 세부지원내용 규모 국비 자부담 (70%) (30%) 안전 일반 650내외개사630이내만원270이상만원 ・・산업안전 예방";
        var groups = ParticipationCategoryProjector.project(List.of(rule(damaged), rule("상시근로자 수가 1�0명 미만일 것")), false);
        assertThat(text(groups, "MATCHING")).doesNotContain("650", "630", "270", "・・");
        assertThat(text(groups, "HEADCOUNT")).doesNotContain("10명", "1�0");
    }
    @Test void 완결문장_경계만_분리하고_예외와_기간_선택관계는_보존한다() {
        var groups = ParticipationCategoryProjector.project(List.of(rule("가. 기업은 업력 3~7년이어야 함 나. 기업은 매출액 100억원 이상이어야 함")), false);
        assertThat(text(groups, "BUSINESS_AGE")).contains("3~7년이어야 합니다.").doesNotContain("나.");
        assertThat(text(groups, "REVENUE")).contains("100억원 이상이어야 합니다.");
        String except = "기업은 자부담 30% 이상을 부담해야 합니다. 다만, 창업 3년 미만 기업은 자부담을 면제할 수 있습니다.";
        assertThat(text(ParticipationCategoryProjector.project(List.of(rule(except)), false), "MATCHING")).contains(except);
    }
    @Test void 실제_구조표는_유형별_금액을_복원하고_평탄화표의_금액은_추측하지_않는다() throws Exception {
        String structured = fixture("clean-structured.txt"), legacy = fixture("clean-legacy.txt");
        var file = DocumentAttachment.create(null, "클린제조환경 공고.pdf", "https://example.org/notice.pdf", "pdf", "application/pdf",
                1L, "hash", structured, AttachmentParseStatus.COMPLETED, null);
        var guide = ParticipationConditionResolver.resolve(legacy, List.of(file), null);
        var attachmentOnly = ParticipationConditionResolver.resolve(null, List.of(file), null);
        assertThat(text(attachmentOnly.categories(), "MATCHING")).contains("모든 유형 공통으로", "안전환경(일반)", "안전환경(고위험업종)");
        String funding = text(guide.categories(), "MATCHING");
        assertThat(entries(guide.categories(), "HEADCOUNT").stream().filter(entry -> entry.rule().text().contains("10명 미만"))).hasSize(1);
        assertThat(entries(guide.categories(), "MATCHING").stream().filter(entry -> entry.rule().text().contains("지원방식은"))).hasSize(1);
        assertThat(funding).contains("안전환경(일반)", "630만원 이내", "270만원 이상", "안전환경(고위험업종)",
                "1,050만원 이내", "450만원 이상", "모든 유형 공통으로").doesNotContain("650내외개사630", "라.", "지원 지원한도", "[표]");
        assertThat(text(guide.categories(), "SECTOR")).contains("고위험업종 유형으로 신청하려면", "C10~C34")
                .doesNotContain("[참고", "영위할 것", "<", "조건.");
        for (var category : guide.categories()) for (var row : category.rows()) for (var entry : row.entries())
            if (!entry.rule().section().equals("REVIEW")) assertThat(entry.rule().text())
                    .doesNotMatch("(?s)^(?:[가-하][.]|[①-⑳]|\\d+[.]).*").doesNotContain("[참고", "・・", "< 소상공인", " 것 나.", "이 법에서", "각 호의 요건")
                    .matches("(?s).*니다[.]$");
        var path = Path.of("build/participation-categories/clean-display.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, new ObjectMapper().writeValueAsString(guide));
        var flat = ParticipationConditionResolver.resolve(legacy, List.of(), null);
        assertThat(text(flat.categories(), "MATCHING")).doesNotContain("630만원", "270만원", "1,050만원", "450만원");
        Files.writeString(path.resolveSibling("clean-flat-display.json"), new ObjectMapper().writeValueAsString(flat));
    }

    @Test void 소수_음수_범위와_긴문장을_번호나_길이제한으로_자르지_않는다() {
        for (String original : List.of("1.5억원 이상이어야 함", "-10% 이상이어야 함", "매출액 1,050만원 이상이어야 함",
                "업력 3~7년이어야 함", "부채비율 0.5% 이하이어야 함")) {
            String displayed = ParticipationDisplayText.sentence(original);
            assertThat(displayed).startsWith(original.substring(0, original.indexOf(' '))).endsWith("니다.");
        }
        String longText = "신청 기업은 " + "사업 수행 시 제출할 계획과 이행 절차를 충분히 설명하고 ".repeat(50) + "계획서를 제출해야 합니다.";
        assertThat(ParticipationDisplayText.sentence(longText)).isEqualTo(longText);
    }

    @Test void 소제목이_별도_필드에_있어도_설명문에_연결한다() {
        var original = rule("산재보험 가입 코드와 일치 필요");
        var scoped = new ParticipationRule("BASIC", "고위험업종", original.text(), "고위험업종 유형", null,
                List.of(), List.of(), List.of(), List.of(), original.sources());
        assertThat(text(ParticipationCategoryProjector.project(List.of(scoped), false), "SECTOR"))
                .contains("고위험업종 유형으로 신청하려면 산재보험 가입 코드가 고위험업종 코드와 일치해야 합니다.");
    }

    @Test void 구조표의_열이나_단위가_불확실하면_금액을_복원하지_않는다() {
        assertThat(ParticipationFundingTable.sentences("[표]\n유형\t국비\t자부담\n유형1\t630만원 이내\t270만원 이상\n유형2\t1,050만원 이내\t450만원 이상\n[/표]"))
                .anySatisfy(sentence -> assertThat(sentence).contains("유형1", "630만원 이내", "270만원 이상"))
                .anySatisfy(sentence -> assertThat(sentence).contains("유형2", "1,050만원 이내", "450만원 이상"));
        for (String table : List.of(
                "[표]\n유형\t국비\t자부담\n일반\t630\t270\n[/표]",
                "[표]\n유형\t국비\t자부담\n일반\t630만원 이내\n[/표]",
                "[표]\n유형\t국비\t자부담\n일반\t630만원 이내\t270만원 이상\n고위험\t1,050만원\t\n[/표]"))
            assertThat(ParticipationFundingTable.sentences(table)).isEmpty();
    }

    @Test void 불확실한_조건을_명시없음으로_바꾸거나_다른유형과_합치지_않는다() {
        var groups = ParticipationCategoryProjector.project(List.of(rule("지원유형 세부지원내용 국비 자부담 650내외개사630이내만원270이상만원")), false);
        assertThat(groups.stream().flatMap(group -> group.rows().stream()).filter(row -> row.code().equals("MATCHING")).findFirst().orElseThrow().status())
                .isEqualTo("REVIEW_REQUIRED");
        var original = rule("자부담 30% 이상을 부담해야 합니다.");
        var variants = List.of("일반 유형", "고위험 유형").stream().map(scope -> new ParticipationRule("FUNDING", original.label(),
                original.text(), scope, null, List.of(), List.of(), List.of(), List.of(), original.sources())).toList();
        assertThat(entries(ParticipationCategoryProjector.project(variants, false), "MATCHING")).hasSize(2);
    }

    @Test void 평탄화된_법령정의_뒤의_번호조건과_추가요건을_누락하지_않는다() {
        String law = "< 소상공인기본법 > 제2조(정의) 이 법에서 소상공인이란 다음 요건을 모두 갖춘 자를 말한다. 1. 상시근로자 수가 10명 미만일 것 2. 업종별 상시근로자 수 등이 대통령령으로 정하는 기준에 해당할 것";
        var groups = ParticipationCategoryProjector.project(List.of(rule(law)), false);
        assertThat(text(groups, "HEADCOUNT")).contains("10명 미만이어야 합니다", "대통령령으로 정하는 기준에 해당해야 합니다");
        String mixed = "(고위험업종 유형) 고위험업종 산재보험 가입 코드와 일치해야 하며, 업력 7년 이상이어야 합니다.";
        var conditions = ParticipationCategoryProjector.project(List.of(rule(mixed)), false);
        assertThat(text(conditions, "BUSINESS_AGE")).contains("7년 이상");
        assertThat(text(conditions, "SECTOR")).contains("산재보험 가입 코드");
    }

    private String fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/participation-conditions/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
