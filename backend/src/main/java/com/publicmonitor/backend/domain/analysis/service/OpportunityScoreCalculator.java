package com.publicmonitor.backend.domain.analysis.service;

import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import java.util.Map;

public final class OpportunityScoreCalculator {

    private OpportunityScoreCalculator() {
    }

    public static int calculate(Map<OpportunityDimensionType, Integer> scores) {
        return calculate(scores, com.publicmonitor.backend.domain.settings.web.dto.EvaluationSettingsData.Preset.FIT);
    }

    public static int calculate(Map<OpportunityDimensionType, Integer> scores,
            com.publicmonitor.backend.domain.settings.web.dto.EvaluationSettingsData.Preset preset) {
        var weights = preset.weights();
        var dimensions = OpportunityDimensionType.values();
        double weightedScore = 0;
        for (int index = 0; index < dimensions.length; index++) {
            weightedScore += scores.getOrDefault(dimensions[index], 0) * weights.get(index) / 100.0;
        }
        int roundedScore = (int) Math.round(weightedScore);
        int companyFitScore = scores.getOrDefault(OpportunityDimensionType.COMPANY_FIT, 0);
        if (companyFitScore <= 40) {
            return Math.min(roundedScore, 49);
        }
        return roundedScore;
    }

    public static OpportunityPriority priority(
            int totalScore,
            int companyFitScore,
            int feasibilityScore,
            int urgencyScore
    ) {
        if (totalScore >= 75
                && companyFitScore >= 50
                && feasibilityScore >= 50) {
            return OpportunityPriority.HIGH;
        }
        if (totalScore >= 50
                && companyFitScore >= 50
                && feasibilityScore >= 50
                && urgencyScore >= 85) {
            return OpportunityPriority.HIGH;
        }
        if (totalScore >= 50) {
            return OpportunityPriority.NORMAL;
        }
        return OpportunityPriority.LOW;
    }
}