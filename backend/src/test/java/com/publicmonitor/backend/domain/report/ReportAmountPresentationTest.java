package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReportAmountPresentationTest {
    @Test void removesOnlyListDecorationAndExactRepeatedParagraphs() {
        String value = "1-3. 지원규모 및 지원기간 o 과제당 연 최대 5억 원, 최대 3년 지원. "
                + "* 정부 예산에 따라 지원금액이 변경될 수 있습니다.\n"
                + "• 과제당 연 최대 5억 원, 최대 3년 지원.\n"
                + "o 정부 예산에 따라 지원금액이 변경될 수 있습니다.";
        assertThat(ReportAmountPresentation.paragraphs(value)).containsExactly(
                "과제당 연 최대 5억 원, 최대 3년 지원.",
                "정부 예산에 따라 지원금액이 변경될 수 있습니다.");
    }

    @Test void retainsValidatedNaturalSentencesAsWritten() {
        String paragraph = "과제당 연구개발비를 연 최대 5억 원까지, 최대 3년 지원합니다. 기업 규모별 자부담 비율이 적용됩니다.";
        assertThat(ReportAmountPresentation.paragraphs(paragraph)).containsExactly(paragraph);
    }

    @Test void equalNumbersWithDifferentScopesOrConditionsAreNotDeduplicated() {
        var paragraphs = List.of("전체 예산: 5억 원", "과제당 지원한도: 5억 원", "연간 지원한도: 5억 원",
                "1차년도 5억 원 (부가세 포함)", "2차년도 5억 원 (부가세 별도)");
        assertThat(ReportAmountPresentation.paragraphs(String.join("\n", paragraphs))).containsExactlyElementsOf(paragraphs);
    }

    @Test void keepsDecimalsRangesPercentagesDatesAndMultiplication() {
        var values = List.of("1.5억 원", "1-3년 지원", "25% 이상 자부담", "2026.10.20 기준 예산", "5억 원 * 2년", "5억원*기업수",
                "총 2억 원 (국비 1억 원 + 지방비 1억 원)", "지원기간: 2026년부터 2028년까지");
        assertThat(ReportAmountPresentation.paragraphs(String.join("\n", values))).containsExactlyElementsOf(values);
    }

    @Test void preservesMandatoryFootnotesAndTaxRestrictionsWhileCleaningMarkers() {
        String value = "o 지원규모: 기업당 2억 원 이내\n* 부가세는 지원하지 않음\n"
                + "※ 자부담 25% 이상이며 현금 부담 10% 이상 필수\n- 협약 후 예산 부족 시 지원 중단 가능";
        assertThat(ReportAmountPresentation.paragraphs(value)).containsExactly("기업당 2억 원 이내", "부가세는 지원하지 않음",
                "자부담 25% 이상이며 현금 부담 10% 이상 필수", "협약 후 예산 부족 시 지원 중단 가능");
    }

    @Test void emptyHeadingsAreHiddenAndAmbiguousTableStillRequiresSourceCheck() {
        assertThat(ReportAmountPresentation.paragraphs("1-3. 지원규모 및 지원기간")).isEmpty();
        assertThat(ReportAmountPresentation.paragraphs("구분 지원대상 지원규모 지원기간 3 5 10"))
                .containsExactly("원문 표에서 금액과 지원 조건을 확인해 주세요.");
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n• 금액·규모: 1-3. 지원규모 및 지원기간");
        assertThat(html).doesNotContain("data-report-section=\"amount\"");
    }

    @Test void rendersLegacyAmountAsEscapedParagraphsWithoutInjectedBulletsOrSummation() {
        String html = ReportEmailTemplate.render("보고서", "▸ 공고\n• 금액·규모: 1-3. 지원규모 및 지원기간\n"
                + "  ↳ o 연간 최대 5억 원\n  ↳ * 최대 3년, 단 예산 변경 가능\n  ↳ o 연간 최대 5억 원\n"
                + "  ↳ ※ <script>조건</script>");
        assertThat(html).contains("data-report-amount-paragraph", "최대 3년, 단 예산 변경 가능", "&lt;script&gt;조건&lt;/script&gt;")
                .doesNotContain("aria-hidden=\"true\"", "1-3.", "o 연간", "* 최대", "15억", "<script>");
        assertThat(html.split("연간 최대 5억 원", -1)).hasSize(2);
        assertThat(html.split("data-report-amount-paragraph", -1)).hasSize(4);
    }
    @Test void cleansTheFiveSavedEnergyBudgetRowsWithoutLosingTheGovernmentFundingCondition() {
        String value = """
                1-3. 지원 규모 및 기간 o 신규예산 : 총 3.2억원 이내
                o 신규예산 : 총 3.2억원 이내
                o 과제별 지원규모 : (총 연구개발기간) 12개월 이내, (총 정부지원연구개발비) 3.2억원 이내
                품목지정 1개 총 3.2억원 1년 이내 3.2억원 이내 *신규예산은 정부지원연구개발비에 한함
                3.2억원 이내 * 신규예산은 정부지원연구개발비에 한함
                """;
        assertThat(ReportAmountPresentation.paragraphs(value)).containsExactly(
                "신규예산 : 총 3.2억원 이내",
                "과제별 지원규모 : (총 연구개발기간) 12개월 이내, (총 정부지원연구개발비) 3.2억원 이내",
                "신규예산은 정부지원연구개발비에 한함");
    }

    @Test void anUncoveredAmountInAFlattenedTableIsNotInventedOrSilentlyOmitted() {
        String value = "신규예산 : 총 3.2억원 이내\n품목지정 1개 총 4억원 1년 이내 2억원 이내";
        assertThat(ReportAmountPresentation.paragraphs(value)).containsExactly(
                "신규예산 : 총 3.2억원 이내", "원문 표에서 금액과 지원 조건을 확인해 주세요.");
        assertThat(ReportAmountPresentation.paragraphs("품목지정 1개 총 3.2억원 1년 이내 3.2억원 이내"))
                .containsExactly("원문 표에서 금액과 지원 조건을 확인해 주세요.");
    }

    @Test void bareAmountRemovalDoesNotConfuseDifferentValuesOrLoseAnUncoveredAmount() {
        assertThat(ReportAmountPresentation.paragraphs("과제당 13.2억원 이내\n3.2억원 이내\n- 5억원"))
                .containsExactly("과제당 13.2억원 이내", "3.2억원 이내", "- 5억원");
    }
    @Test void aDifferentDurationInAFlattenedTableStillRequiresSourceCheck() {
        String value = "과제당 3.2억원 이내, 12개월 이내\n품목지정 1개 총 3.2억원 2년 이내 3.2억원 이내";
        assertThat(ReportAmountPresentation.paragraphs(value)).containsExactly(
                "과제당 3.2억원 이내, 12개월 이내", "원문 표에서 금액과 지원 조건을 확인해 주세요.");
    }
    @Test void removesBudgetSectionGlyphsAndHeadingsWithoutLosingGrantScopeOrNumbers() {
        String value = "903백만원\n□ 사업기간/예산(정부출연금): 2026년~2029년 / 과제당 총 9.03억원 이내\n"
                + "□ 지원규모: 중소기업은 정부지원연구개발비 75% 이내, 기관부담은 25% 이상";
        assertThat(ReportAmountPresentation.paragraphs(value)).containsExactly("903백만원",
                "(정부출연금) 2026년~2029년 / 과제당 총 9.03억원 이내",
                "중소기업은 정부지원연구개발비 75% 이내, 기관부담은 25% 이상");
    }

    @Test void longAmountProseSplitsAtSentenceBoundariesWithoutTruncationOrChangedTerms() {
        String value = "과제당 총 정부출연금은 9.03억원 이내이며 사업기간은 2026년부터 2029년까지입니다. "
                + "1차년도부터 최종연도까지 연차별 지급금액은 협약에 따라 정하며 선정평가와 예산 사정에 따라 지원 규모가 변경될 수 있습니다. "
                + "중소기업은 정부지원연구개발비 75% 이내이며 기관부담연구개발비는 25% 이상으로 현금 부담 조건을 반드시 충족해야 합니다. "
                + "신규 채용의 인건비 인정 여부와 부가가치세 지원 제외 조건은 협약 시 확인합니다.";
        var paragraphs = ReportAmountPresentation.paragraphs(value);
        assertThat(paragraphs).hasSize(4);
        assertThat(String.join(" ", paragraphs)).isEqualTo(value).doesNotContain("…", "...");
    }

    @Test void squareChoicesAreNotMistakenForDecorativeBudgetHeadings() {
        assertThat(ReportAmountPresentation.paragraphs("□ A형 3억원 / ■ B형 5억원"))
                .containsExactly("□ A형 3억원 / ■ B형 5억원");
    }
}
