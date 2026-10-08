package com.publicmonitor.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class OpportunityRankingCalculatorTest {
    private final OpportunityRankingCalculator calculator = new OpportunityRankingCalculator(new ObjectMapper());

    @ParameterizedTest
    @MethodSource("rankings")
    void 저장된_차원으로_같은_점수와_우선순위를_계산한다(int fit, int value, int feasible, int urgency,
            int score, OpportunityPriority priority) {
        String json = """
                {"dimensions":[{"type":"COMPANY_FIT","score":%d},
                {"type":"BUSINESS_VALUE","score":%d},{"type":"FEASIBILITY","score":%d},
                {"type":"URGENCY","score":%d}]}
                """.formatted(fit, value, feasible, urgency);
        var result = calculator.readStored(99, json);
        assertThat(result.score()).isEqualTo(score);
        assertThat(result.priority()).isEqualTo(priority);
        assertThat(result.malformed()).isFalse();
    }

    static Stream<Arguments> rankings() {
        return Stream.of(
                Arguments.of(80, 70, 60, 90, 75, OpportunityPriority.HIGH),
                Arguments.of(55, 55, 55, 90, 59, OpportunityPriority.HIGH),
                Arguments.of(60, 60, 60, 60, 60, OpportunityPriority.NORMAL),
                Arguments.of(40, 100, 100, 100, 49, OpportunityPriority.LOW),
                Arguments.of(90, 90, 20, 90, 76, OpportunityPriority.NORMAL),
                Arguments.of(50, 50, 50, 85, 54, OpportunityPriority.HIGH),
                Arguments.of(50, 50, 50, 84, 53, OpportunityPriority.NORMAL),
                Arguments.of(0, 0, 0, 0, 0, OpportunityPriority.LOW));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void 평가가_없으면_기존_점수만_보존한다(String json) {
        var result = calculator.readStored(72, json);
        assertThat(result.score()).isEqualTo(72);
        assertThat(result.priority()).isNull();
        assertThat(result.malformed()).isFalse();
    }

    @Test
    void 누락된_알려진_차원은_0점이고_알_수_없는_차원은_건너뛴다() {
        var result = calculator.readStored(null, """
                {"dimensions":[{"type":"COMPANY_FIT","score":80},
                {"type":"EVIDENCE_CONFIDENCE","score":null},
                {"type":"FUTURE_DIMENSION","score":"unknown"}]}
                """);
        assertThat(result.score()).isEqualTo(40);
        assertThat(result.priority()).isEqualTo(OpportunityPriority.LOW);
        assertThat(result.malformed()).isFalse();
        assertThat(calculator.readStored(null, "{\"dimensions\":[]}").score()).isZero();
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void 손상된_알려진_차원은_추정_분류하지_않는다(String json) {
        var result = calculator.readStored(72, json);
        assertThat(result.score()).isEqualTo(72);
        assertThat(result.priority()).isNull();
        assertThat(result.malformed()).isTrue();
    }

    static Stream<String> malformed() {
        return Stream.of("{broken", "null", "[]", "{}", "{\"dimensions\":null}",
                "{\"dimensions\":[null]}", "{\"dimensions\":[{}]}",
                "{\"dimensions\":[{\"type\":null,\"score\":50}]}",
                "{\"dimensions\":[{\"type\":\"COMPANY_FIT\",\"score\":50},{\"type\":\"COMPANY_FIT\",\"score\":50}]}",
                "{\"dimensions\":[{\"type\":\"COMPANY_FIT\",\"score\":null}]}",
                "{\"dimensions\":[{\"type\":\"COMPANY_FIT\"}]}",
                "{\"dimensions\":[{\"type\":\"COMPANY_FIT\",\"score\":-1}]}",
                "{\"dimensions\":[{\"type\":\"COMPANY_FIT\",\"score\":101}]}",
                "{\"dimensions\":[{\"type\":\"COMPANY_FIT\",\"score\":99999999999999999}]}",
                "{\"dimensions\":[{\"type\":\"COMPANY_FIT\",\"score\":50.5}]}",
                "{\"dimensions\":[{\"type\":\"COMPANY_FIT\",\"score\":\"50\"}]}");
    }
}
