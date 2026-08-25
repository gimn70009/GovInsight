package com.publicmonitor.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpportunityScoreCalculatorTest {

    @Test
    void givesCompanyFitTheLargestWeight() {
        int score = OpportunityScoreCalculator.calculate(Map.of(
                OpportunityDimensionType.COMPANY_FIT, 80,
                OpportunityDimensionType.BUSINESS_VALUE, 60,
                OpportunityDimensionType.FEASIBILITY, 50,
                OpportunityDimensionType.URGENCY, 40,
                OpportunityDimensionType.EVIDENCE_CONFIDENCE, 50
        ));

        assertThat(score).isEqualTo(63);
    }

    @Test
    void capsIndirectOpportunityBelowReviewThreshold() {
        int score = OpportunityScoreCalculator.calculate(Map.of(
                OpportunityDimensionType.COMPANY_FIT, 40,
                OpportunityDimensionType.BUSINESS_VALUE, 90,
                OpportunityDimensionType.FEASIBILITY, 90,
                OpportunityDimensionType.URGENCY, 90,
                OpportunityDimensionType.EVIDENCE_CONFIDENCE, 90
        ));

        assertThat(score).isEqualTo(49);
    }

    @Test
    void doesNotRaisePriorityWhenOnlyUrgencyIsHigh() {
        OpportunityPriority priority = OpportunityScoreCalculator.priority(
                59,
                60,
                45,
                85
        );

        assertThat(priority).isEqualTo(OpportunityPriority.NORMAL);
    }

    @Test
    void raisesPriorityForUrgentAndActionableOpportunity() {
        OpportunityPriority priority = OpportunityScoreCalculator.priority(
                60,
                60,
                60,
                90
        );

        assertThat(priority).isEqualTo(OpportunityPriority.HIGH);
    }

    @Test
    void doesNotRaisePriorityWhenHighTotalLacksCompanyFit() {
        OpportunityPriority priority = OpportunityScoreCalculator.priority(
                75,
                40,
                80,
                90
        );

        assertThat(priority).isEqualTo(OpportunityPriority.NORMAL);
    }
    @Test
    void raisesPriorityWhenTotalScoreIsHigh() {
        OpportunityPriority priority = OpportunityScoreCalculator.priority(
                80,
                70,
                65,
                40
        );

        assertThat(priority).isEqualTo(OpportunityPriority.HIGH);
    }
}