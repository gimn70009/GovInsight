package com.publicmonitor.backend.domain.document.service;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class ParticipationFactsTest {
    @TestFactory Stream<DynamicTest> sharedFactContract() throws Exception {
        try (var stream = getClass().getResourceAsStream("/participation-conditions/semantic-facts.json")) {
            var rows = JsonMapper.builder().build().readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return java.util.stream.StreamSupport.stream(rows.spliterator(), false).map(row -> DynamicTest.dynamicTest(
                    row.get("name").asText(), () -> assertThat(ParticipationFacts.equivalent(
                            row.get("text").asText(), row.get("quote").asText())).isEqualTo(row.get("equivalent").asBoolean())));
        }
    }

    @Test void readableLawSummaryKeepsOriginalQuoteAndCannotChangeEligibility() {
        String quote = "「중소기업기본법」 제2조에 따른 중소기업";
        var display = ParticipationFocusedText.prepare("BUSINESS_FORM", "중소기업이 지원 대상입니다.",
                quote, "신청자격", "ELIGIBILITY");
        assertThat(display).isNotNull();
        assertThat(display.quote()).isEqualTo(quote);
        assertThat(ParticipationFocusedText.prepare("BUSINESS_FORM", "중견기업이 지원 대상입니다.",
                quote, "신청자격", "ELIGIBILITY")).isNull();
    }
}
