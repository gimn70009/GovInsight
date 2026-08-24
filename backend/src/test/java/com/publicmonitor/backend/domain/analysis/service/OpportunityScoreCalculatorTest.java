package com.publicmonitor.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import org.junit.jupiter.api.Test;

class OpportunityScoreCalculatorTest {

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