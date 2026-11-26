package com.publicmonitor.backend.domain.analysis.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisResultUnicodeValidationTest {
    private static final String HWP_SYMBOL = new String(Character.toChars(0xF02B1));

    @Test void sourceExcerptCountsSupplementarySymbolsAsOneCharacterAndPreservesThem() {
        String excerpt = "가".repeat(2976) + HWP_SYMBOL.repeat(19);
        assertThat(excerpt.length()).isEqualTo(3014);
        assertThat(excerpt.codePointCount(0, excerpt.length())).isEqualTo(2995);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var finding = new AnalysisResultRequest.LegalRiskFinding(
                    "RESULT_IP_REUSE", "ASSESSMENT_INCOMPLETE", "원문을 추가로 확인해야 합니다.", excerpt);
            assertThat(validator.validate(finding)).isEmpty();
            assertThat(finding.evidenceExcerpt()).isEqualTo(excerpt);
            assertThat(validator.validateValue(AnalysisResultRequest.LegalRiskFinding.class,
                    "evidenceExcerpt", HWP_SYMBOL.repeat(3000))).isEmpty();
            assertThat(validator.validateValue(AnalysisResultRequest.LegalRiskFinding.class,
                    "evidenceExcerpt", HWP_SYMBOL.repeat(3001))).hasSize(1);
            assertThat(validator.validateValue(AnalysisResultRequest.LegalRiskFinding.class,
                    "evidenceExcerpt", "가".repeat(3001))).hasSize(1);
        }
    }

    @Test void nestedTextUsesCodePointsWhileArrayLimitsAndRequiredTextRemainEnforced() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validateValue(AnalysisResultRequest.AnalysisResult.class,
                    "keyPoints", List.of(HWP_SYMBOL.repeat(1000)))).isEmpty();
            assertThat(validator.validateValue(AnalysisResultRequest.AnalysisResult.class,
                    "keyPoints", List.of(HWP_SYMBOL.repeat(1001)))).hasSize(1);
            assertThat(validator.validateValue(AnalysisResultRequest.AnalysisResult.class,
                    "keyPoints", java.util.Collections.nCopies(9, "유효한 핵심 내용"))).hasSize(1);
            assertThat(validator.validate(new AnalysisResultRequest.Section(" ", " "))).hasSize(2);
        }
    }
}
