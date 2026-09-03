package com.publicmonitor.backend.domain.analysis.service;

import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import java.util.Map;

public final class OpportunityScoreCalculator {

    private static final Map<OpportunityDimensionType, Double> WEIGHTS = Map.of(
            OpportunityDimensionType.COMPANY_FIT, 0.50,
            OpportunityDimensionType.BUSINESS_VALUE, 0.20,
            OpportunityDimensionType.FEASIBILITY, 0.20,
            OpportunityDimensionType.URGENCY, 0.10
    );

    private OpportunityScoreCalculator() {
    }

    public static int calculate(Map<OpportunityDimensionType, Integer> scores) {
        double weightedScore = WEIGHTS.entrySet().stream()
                .mapToDouble(entry -> scores.getOrDefault(entry.getKey(), 0) * entry.getValue())
                .sum();
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