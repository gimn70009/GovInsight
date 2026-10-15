package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ParticipationPlainLanguageTest {
    @Test
    void 공식_창업공고의_대상과_업력을_설명하고_원문을_보존한다() throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/participation-conditions/bio-open-innovation.txt")) {
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var attachment = DocumentAttachment.create(null, "바이오 참여기업 모집공고.hwpx", "https://example.org/bio.hwpx", "hwpx",
                "application/octet-stream", 1L, "hash", original, AttachmentParseStatus.COMPLETED, null);
        var guide = ParticipationConditionResolver.resolve(null, List.of(attachment), null);
        Path output = Path.of("build/participation-guide-checks/bio-open-innovation-guide.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writeValueAsString(guide), StandardCharsets.UTF_8);
        assertThat(guide.conditions()).allSatisfy(row -> assertThat(original).contains(row.source().excerpt()));
        assertThat(guide.rules().stream().filter(row -> row.label().equals("창업기업 자격")).toList()).singleElement().satisfies(row -> {
            assertThat(row.label()).isEqualTo("창업기업 자격");
            assertThat(row.text()).contains("신청 대상은 중소기업 대표자입니다.", "모집공고일 기준", "다음 중 하나", "7년 이내 창업기업", "10년 이내 신산업 분야");
            assertThat(row.text()).doesNotContain("제2조", "2019", "▶", "동법", "공고문의 신청자격");
            assertThat(row.sources()).hasSize(2);
            assertThat(row.scope()).isEqualTo("협업과제 추진을 희망하는 수요기업과 창업기업");
            assertThat(String.join(" ", row.details())).contains("2019년 10월 2일~2026년 10월 1일", "2016년 10월 2일~2026년 10월 1일",
                    "개인사업자는 사업자등록증", "법인사업자는 등기사항전부증명서", "개업연월일", "회사성립연월일",
                    "공동(각자)대표자 전원", "해당되지 않아야 합니다", "사업 개시 및 사업자 등록을 완료",
                    "다수의 사업자등록증", "이전에 개시한 사업자", "창업 기준 충족일", "제25조제4항");
            assertThat(row.details()).filteredOn(value -> value.startsWith("법적 자격 기준: ")).hasSize(3);
            assertThat(row.text().split("\\n")).hasSize(4);
        });
    }

    private static String criterion(int years, String operator, String law) {
        return "｢" + law + "｣ 제3조제2항에 따른 중소기업의 대표자이자, 신청마감일 기준 "
                + "｢창업 지원법｣ 제8조제1호에 따른 창업 후 " + years + "년 미만 창업기업 " + operator
                + " 동법 제12조제5항에 따른 창업 후 " + (years + 4) + "년 이내 신산업 분야 창업·중소기업";
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 8})
    void 법령_연수_기준일을_공고별_상수_없이_설명한다(int years) {
        String original = "□ 신청자격\n◦ (창업기업) " + criterion(years, "또는", "기업 지원법")
                + "\n▶창업 후 " + years + "년 미만 기업 (신청가능 창업(설립)일 기준: 2020년 1월 2일~2025년 1월 1일)"
                + "\n* 단, 해외 법인은 신청 불가";
        var row = ParticipationConditionResolver.resolve(original, List.of(), null).rules().getFirst();
        assertThat(row.text()).contains("신청마감일 기준", "다음 중 하나", "창업 후 " + years + "년 미만", "창업 후 " + (years + 4) + "년 이내");
        assertThat(String.join(" ", row.details())).contains("2020년 1월 2일~2025년 1월 1일", "｢기업 지원법｣ 제3조제2항", "｢창업 지원법｣ 제12조제5항");
        assertThat(String.join(" ", row.exceptions())).contains("해외 법인", "신청할 수 없습니다");
        assertThat(row.sources().getFirst().excerpt()).isEqualTo(original);
    }

    @Test
    void 모두_충족과_하나_충족의_관계를_바꾸지_않는다() {
        var explanation = ParticipationPlainLanguage.explain("(창업기업) " + criterion(4, "및", "기업 지원법"), "기업 규모");
        assertThat(explanation.text()).contains("모두 충족").doesNotContain("중 하나");
    }

    @ParameterizedTest
    @ValueSource(strings = {"이며 매출액 20억원 미만인 기업", " 중 수도권 기업은 제외", "으로 법원 회생인가를 받은 기업에 한함"})
    void 해석하지_못한_추가_제한을_요약으로_숨기지_않는다(String qualifier) {
        String raw = criterion(4, "또는", "기업 지원법") + qualifier;
        assertThat(ParticipationPlainLanguage.explain(raw, "기업 규모")).isNull();
        var row = ParticipationConditionResolver.resolve("□ 신청자격\n◦ (창업기업) " + raw, List.of(), null).rules().getFirst();
        assertThat(row.text()).contains(qualifier.replace("한함", "한합니다"));
    }

    @Test
    void 단독_법적_정의도_대상을_먼저_설명한다() {
        var row = ParticipationConditionResolver.resolve("□ 신청자격\n◦ ｢지역기업 육성법｣ 제4조제2항에 따른 소기업", List.of(), null).rules().getFirst();
        assertThat(row.text()).isEqualTo("소기업이 대상입니다.");
        assertThat(String.join(" ", row.details())).contains("｢지역기업 육성법｣ 제4조제2항");
    }

    @Test
    void 근거_없는_동법이나_법령_연결을_추정하지_않는다() {
        assertThat(ParticipationPlainLanguage.explain("동법 제2조에 따른 소기업", "기업 규모")).isNull();
        String raw = "｢첫째법｣ 제1조에 따른 ｢둘째법｣ 제2조에 따른 ｢셋째법｣ 제3조에 따른 "
                + "중소기업의 대표자이자, 공고일 기준 창업 후 3년 이내 창업기업 또는 창업 후 6년 이내 신산업 분야 창업·중소기업";
        var explanation = ParticipationPlainLanguage.explain(raw, "기업 규모");
        assertThat(explanation.legalDetails()).containsExactly("법적 자격 기준: " + raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A자금 신청기업", "서울 소재 기업", "2025년 선정기업", "협업을 희망하는 수요기업과 창업기업 중 소기업에 한함", "실증을 희망하는 주관기업과 협력기업"})
    void 다른_적용_범위는_중복으로_합치지_않는다(String scope) {
        String original = "□ 모집대상: " + scope + "\n◦ 창업기업 : " + criterion(3, "또는", "기업 지원법")
                + "\n□ 지원내용: 사업화 자금\n□ 신청자격\n◦ (창업기업) " + criterion(3, "또는", "기업 지원법")
                + "\n▶사업자 등록을 완료해야 함";
        assertThat(ParticipationConditionResolver.resolve(original, List.of(), null).rules()).hasSize(2);
    }

    @Test
    void 다른_법령이나_수치의_자격은_합치지_않는다() {
        String original = "□ 신청자격\n◦ (창업기업) " + criterion(3, "또는", "첫째법")
                + "\n◦ (창업기업) " + criterion(3, "또는", "둘째법")
                + "\n◦ (창업기업) " + criterion(4, "또는", "첫째법");
        assertThat(ParticipationConditionResolver.resolve(original, List.of(), null).rules()).hasSize(3);
    }

    @Test
    void 다양한_삼각_목록과_하위_불릿을_상위_조건에서_분리한다() {
        var rows = ParticipationConditionResolver.resolve("□ 신청자격\n◦ 창업 후 4년 이내 기업\n▶매출액 3억원 미만\n▷상시근로자 5명 이상"
                + "\n• 공동대표 전원 요건 충족\n◦ 수출 기업", List.of(), null).rules();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().text()).doesNotContain("매출액", "공동대표");
        assertThat(String.join(" ", rows.getFirst().details())).contains("매출액 3억원 미만", "상시근로자 5명 이상", "공동대표 전원");
        assertThat(ParticipationConditionResolver.resolve("□ 신청자격\n• 소기업\n• 소재지 서울", List.of(), null).rules()).hasSize(2);
    }

    @Test
    void 공통_사업_대상을_보존하며_개요와_상세_자격을_합친다() {
        String criterion = criterion(5, "또는", "기업 지원법");
        String scope = "실증 과제 수행을 희망하는 창업기업과 협력기업";
        String original = "□ 모집대상: " + scope + "\n◦ 창업기업 : " + criterion
                + "\n□ 지원내용: 실증 지원\n□ 신청자격\n◦ (창업기업) " + criterion + "\n▶사업자 등록을 완료해야 함";
        assertThat(ParticipationConditionResolver.resolve(original, List.of(), null).rules()).singleElement().satisfies(row -> {
            assertThat(row.scope()).isEqualTo(scope);
            assertThat(row.details()).contains("사업자 등록을 완료해야 합니다.");
            assertThat(row.sources()).hasSize(2);
        });
    }
}
