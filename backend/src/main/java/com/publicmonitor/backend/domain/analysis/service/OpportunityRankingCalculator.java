package com.publicmonitor.backend.domain.analysis.service;

import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OpportunityRankingCalculator {
    private final ObjectMapper objectMapper;

    public record Ranking(Integer score, OpportunityPriority priority, boolean malformed) {}

    public static Ranking calculate(Map<OpportunityDimensionType, Integer> scores) {
        int score = OpportunityScoreCalculator.calculate(scores);
        return new Ranking(score, OpportunityScoreCalculator.priority(score,
                scores.getOrDefault(OpportunityDimensionType.COMPANY_FIT, 0),
                scores.getOrDefault(OpportunityDimensionType.FEASIBILITY, 0),
                scores.getOrDefault(OpportunityDimensionType.URGENCY, 0)), false);
    }

    public Ranking readStored(Integer fallbackScore, String assessment) {
        if (assessment == null || assessment.isBlank()) {
            return new Ranking(fallbackScore, null, false);
        }
        try {
            JsonNode dimensions = objectMapper.readTree(assessment).path("dimensions");
            if (!dimensions.isArray()) return new Ranking(fallbackScore, null, true);
            Map<OpportunityDimensionType, Integer> scores = new EnumMap<>(OpportunityDimensionType.class);
            for (JsonNode dimension : dimensions) {
                if (!dimension.isObject() || !dimension.path("type").isString()) {
                    return new Ranking(fallbackScore, null, true);
                }
                OpportunityDimensionType type;
                try {
                    type = OpportunityDimensionType.valueOf(dimension.path("type").asString());
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                JsonNode score = dimension.path("score");
                if (!score.isIntegralNumber() || !score.canConvertToInt()
                        || score.intValue() < 0 || score.intValue() > 100 || scores.containsKey(type)) {
                    return new Ranking(fallbackScore, null, true);
                }
                scores.put(type, score.intValue());
            }
            return calculate(scores);
        } catch (RuntimeException exception) {
            return new Ranking(fallbackScore, null, true);
        }
    }
}
