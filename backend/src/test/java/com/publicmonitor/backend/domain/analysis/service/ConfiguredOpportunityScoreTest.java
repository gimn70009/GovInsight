package com.publicmonitor.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.settings.web.dto.EvaluationSettingsData.Preset;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

class ConfiguredOpportunityScoreTest {
    @ParameterizedTest @CsvSource({"FIT,66", "GROWTH,65", "FEASIBILITY,63"})
    void storedAndNewRankingsUseTheSnapshotWeights(Preset preset, int expected) {
        var scores = Map.of(OpportunityDimensionType.COMPANY_FIT, 80, OpportunityDimensionType.BUSINESS_VALUE, 60,
                OpportunityDimensionType.FEASIBILITY, 50, OpportunityDimensionType.URGENCY, 40);
        assertThat(OpportunityScoreCalculator.calculate(scores, preset)).isEqualTo(expected);
        var json = """
                {"preset":"%s","settingsRevision":2,"dimensions":[
                {"type":"COMPANY_FIT","score":80},{"type":"BUSINESS_VALUE","score":60},
                {"type":"FEASIBILITY","score":50},{"type":"URGENCY","score":40}]}
                """.formatted(preset);
        assertThat(new OpportunityRankingCalculator(new ObjectMapper()).readStored(99, json).score()).isEqualTo(expected);
    }

    @ParameterizedTest @EnumSource(Preset.class)
    void changingWeightsNeverPromotesUnrelatedOpportunity(Preset preset) {
        assertThat(OpportunityScoreCalculator.calculate(Map.of(
                OpportunityDimensionType.COMPANY_FIT, 40, OpportunityDimensionType.BUSINESS_VALUE, 100,
                OpportunityDimensionType.FEASIBILITY, 100, OpportunityDimensionType.URGENCY, 100), preset)).isEqualTo(49);
    }
}
