package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Small development corpus with Codex-reviewed labels, not an independent accuracy benchmark. */
class SimilarNoticeEvaluationTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final SimilarNoticeService SERVICE = new SimilarNoticeService(null, null, MAPPER);

    record Notice(long id, String title, HybridNoticeRanker.Features features, boolean reliable) {}

    static JsonNode corpus() throws IOException {
        try (var input = SimilarNoticeEvaluationTest.class.getResourceAsStream("/similarity/real-notices-20260910.json")) {
            return MAPPER.readTree(input.readAllBytes());
        }
    }

    static List<Notice> notices(JsonNode corpus, boolean legacy) {
        var notices = new ArrayList<Notice>();
        for (JsonNode row : corpus.path("notices")) {
            DocumentAnalysis analysis = BeanUtils.instantiateClass(DocumentAnalysis.class);
            ReflectionTestUtils.setField(analysis, "summary", row.path("summary").asText());
            ReflectionTestUtils.setField(analysis, "comparisonSummary", row.path("comparison").toString());
            String purpose = legacy ? row.path("comparison").path("purpose").asText()
                    : ReflectionTestUtils.invokeMethod(SERVICE, "purpose", row.path("content").asText(), analysis);
            Set<String> titleTerms = ReflectionTestUtils.invokeMethod(SERVICE, "topicTerms", row.path("title").asText(), row.path("organization").asText());
            Set<String> purposeTerms = ReflectionTestUtils.invokeMethod(SERVICE, "topicTerms", purpose, row.path("organization").asText());
            notices.add(new Notice(row.path("id").asLong(), row.path("title").asText(),
                    new HybridNoticeRanker.Features(titleTerms, purposeTerms),
                    !NoticeSearchText.hasUnknown(row.path("similarityProfile").asText())));
        }
        return notices;
    }

    static double similarity(JsonNode corpus, long a, long b) {
        return similarity(corpus, a, b, false);
    }

    static double similarity(JsonNode corpus, long a, long b, boolean cleanVectors) {
        for (JsonNode pair : corpus.path("pairs")) {
            if (pair.path("left").asLong() == Math.min(a, b) && pair.path("right").asLong() == Math.max(a, b)) {
                return pair.path(cleanVectors ? "cleanSemanticScore" : "semanticScore").asDouble();
            }
        }
        throw new IllegalArgumentException("Missing pair");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retrievesCrossPostedProgramsWithoutConfusingUnrelatedPurposes(boolean cleanVectors) throws IOException {
        JsonNode corpus = corpus();
        List<Notice> notices = notices(corpus, false);
        var statistics = HybridNoticeRanker.Statistics.of(notices.stream().map(Notice::features).toList());
        Map<Long, Set<Long>> selected = new HashMap<>();
        for (Notice current : notices) {
            var candidates = new ArrayList<HybridNoticeRanker.Candidate>();
            for (Notice other : notices) {
                if (current.id == other.id) continue;
                List<String> shared = ReflectionTestUtils.invokeMethod(SERVICE, "sharedTopicTerms", current.features.terms, other.features.terms);
                candidates.add(new HybridNoticeRanker.Candidate(other.id, similarity(corpus, current.id, other.id, cleanVectors),
                        other.features, shared, cleanVectors || current.reliable && other.reliable));
            }
            Set<Long> ids = new LinkedHashSet<>();
            HybridNoticeRanker.rank(current.features, candidates, statistics.excluding(current.features))
                    .stream().limit(3).forEach(match -> ids.add(match.id()));
            selected.put(current.id, ids);
        }
        int tp = 0, fp = 0, fn = 0, tn = 0, review = 0;
        var errors = new ArrayList<String>();
        for (JsonNode pair : corpus.path("pairs")) {
            long left = pair.path("left").asLong(), right = pair.path("right").asLong();
            String label = pair.path("label").asText();
            if (label.equals("review")) { review++; continue; }
            for (boolean reverse : List.of(false, true)) {
                boolean predicted = selected.get(reverse ? right : left).contains(reverse ? left : right);
                boolean expected = label.equals("similar");
                if (expected && predicted) tp++;
                else if (expected) fn++;
                else if (predicted) fp++;
                else tn++;
                if (predicted != expected) errors.add(left + " / " + right + " reverse=" + reverse + " expected=" + label);
            }
        }
        System.out.printf("NOTICE_EVAL cleanVectors=%s directional TP=%d FP=%d FN=%d TN=%d reviewPairs=%d%n", cleanVectors, tp, fp, fn, tn, review);
        assertThat(errors).isEmpty();
        assertThat(tp).isEqualTo(4);
        assertThat(review).isEqualTo(1);
    }
}
