package com.publicmonitor.backend.domain.document.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Query-time lexical retrieval and rank fusion; no network or model calls. */
final class HybridNoticeRanker {
    private static final int WINDOW = 20;
    private static final int RRF_K = 60;
    private static final List<java.util.regex.Pattern> THEME_PATTERNS = List.of(
            "순환경제|재제조", "규제샌드박스|규제특례",
            "국제공동연구|국제협력|기술협력", "공연|예술|창작",
            "반도체|디스플레이|제조|설비|기계|공장|예지보전|결함탐지",
            "전산|유지보수|유지관리|it")
            .stream().map(java.util.regex.Pattern::compile).toList();

    record Candidate(long id, double semantic, Set<String> title, Set<String> purpose,
                     List<String> sharedTopics) {
        Set<String> terms() {
            Set<String> terms = new HashSet<>(title);
            terms.addAll(purpose);
            return terms;
        }
        int length() { return 2 * title.size() + purpose.size(); }
    }
    record Match(long id, double rankScore, String basis) {}
    private record Score(Candidate candidate, double lexical) {}

    static List<Match> rank(Set<String> queryTitle, Set<String> queryPurpose, List<Candidate> candidates) {
        Set<String> query = new HashSet<>(queryTitle);
        query.addAll(queryPurpose);
        if (query.isEmpty() || candidates.isEmpty()) return List.of();
        Map<String, Integer> frequency = new HashMap<>();
        for (Candidate item : candidates) {
            for (String term : item.terms()) frequency.merge(term, 1, Integer::sum);
        }
        double averageLength = candidates.stream().mapToInt(Candidate::length).average().orElse(1);
        List<Score> scores = new ArrayList<>();
        for (Candidate item : candidates) {
            double lexical = 0;
            for (String term : query) {
                int tf = (item.title().contains(term) ? 2 : 0) + (item.purpose().contains(term) ? 1 : 0);
                if (tf == 0) continue;
                double idf = Math.log(1 + (candidates.size() - frequency.getOrDefault(term, 0) + 0.5)
                        / (frequency.getOrDefault(term, 0) + 0.5));
                lexical += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * item.length() / Math.max(1, averageLength)));
            }
            scores.add(new Score(item, lexical));
        }
        // Retrieval lanes are independent. Acceptance below is distinct from being retrieved.
        Map<Long, Integer> semanticRanks = ranks(scores.stream().filter(s -> Double.isFinite(s.candidate.semantic) && s.candidate.semantic >= 0.65)
                .sorted(Comparator.comparingDouble((Score s) -> s.candidate.semantic).reversed()
                        .thenComparingLong(s -> s.candidate.id)).toList(), true);
        Map<Long, Integer> lexicalRanks = ranks(scores.stream().filter(s -> s.lexical > 0)
                .sorted(Comparator.comparingDouble(Score::lexical).reversed()
                        .thenComparingLong(s -> s.candidate.id)).toList(), false);
        List<Match> result = new ArrayList<>();
        Map<Long, Double> semanticScores = new HashMap<>();
        for (Score score : scores) {
            Candidate item = score.candidate;
            semanticScores.put(item.id, Double.isFinite(item.semantic) ? item.semantic : -1.0);
            boolean semanticLane = Double.isFinite(item.semantic) && semanticRanks.containsKey(item.id);
            boolean lexicalLane = lexicalRanks.containsKey(item.id);
            if (!semanticLane && !lexicalLane) continue;
            boolean semanticAvailable = Double.isFinite(item.semantic);
            boolean corroborated = semanticAvailable && item.semantic >= 0.78 && !item.sharedTopics.isEmpty();
            Set<String> purposeOverlap = new HashSet<>(queryPurpose);
            purposeOverlap.retainAll(item.purpose);
            long overlap = query.stream().filter(item.terms()::contains).count();
            // Containment is symmetric: a focused notice can be part of a broader call.
            double coverage = (double) overlap / Math.max(1, Math.min(query.size(), item.terms().size()));
            boolean lexicalMatch = lexicalLane && item.semantic >= 0.65
                    && coverage >= 0.6 && purposeOverlap.size() >= 2;
            boolean semanticMatch = !corroborated && !lexicalMatch && semanticLane && item.semantic >= 0.90
                    && !queryPurpose.isEmpty() && !item.purpose.isEmpty()
                    && !conflictingThemes(query, item.terms());
            double purposeCoverage = (double) purposeOverlap.size()
                    / Math.max(1, Math.min(queryPurpose.size(), item.purpose.size()));
            boolean lexicalOnly = !semanticAvailable && lexicalLane && coverage >= 0.8
                    && purposeCoverage >= 0.8 && purposeOverlap.size() >= 3
                    && !conflictingThemes(query, item.terms());
            if (!corroborated && !lexicalMatch && !semanticMatch && !lexicalOnly) continue;
            double fused = (semanticLane ? 1.0 / (RRF_K + semanticRanks.get(item.id)) : 0)
                    + (lexicalLane ? 1.0 / (RRF_K + lexicalRanks.get(item.id)) : 0);
            String basis = lexicalOnly ? "LEXICAL_ONLY" : corroborated ? "HYBRID" : lexicalMatch ? "LEXICAL" : "SEMANTIC";
            result.add(new Match(item.id, fused, basis));
        }
        return result.stream().sorted(Comparator.comparingDouble(Match::rankScore).reversed()
                .thenComparing(Comparator.comparingDouble((Match m) -> semanticScores.get(m.id)).reversed())
                .thenComparingLong(Match::id)).toList();
    }

    private static Map<Long, Integer> ranks(List<Score> scores, boolean semantic) {
        Map<Long, Integer> result = new HashMap<>();
        double previous = Double.NaN;
        int rank = 0;
        for (int i = 0; i < Math.min(WINDOW, scores.size()); i++) {
            Score item = scores.get(i);
            double value = semantic ? item.candidate.semantic : item.lexical;
            if (Double.compare(previous, value) != 0) rank = i + 1;
            result.put(item.candidate.id, rank);
            previous = value;
        }
        return result;
    }

    private static boolean conflictingThemes(Set<String> left, Set<String> right) {
        Set<Integer> a = themes(left);
        Set<Integer> b = themes(right);
        // This only guards known obvious mismatches; it is not a general semantic classifier.
        return !a.isEmpty() && !b.isEmpty() && a.stream().noneMatch(b::contains);
    }

    private static Set<Integer> themes(Set<String> terms) {
        Set<Integer> result = new HashSet<>();
        for (int i = 0; i < THEME_PATTERNS.size(); i++) {
            var pattern = THEME_PATTERNS.get(i);
            if (terms.stream().anyMatch(term -> pattern.matcher(term).find())) result.add(i);
        }
        return result;
    }
}
