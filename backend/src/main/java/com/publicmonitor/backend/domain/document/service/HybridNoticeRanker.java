package com.publicmonitor.backend.domain.document.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Query-time lexical retrieval and rank fusion; no network or model calls. */
final class HybridNoticeRanker {
    private static final int WINDOW = 20;
    private static final int RRF_K = 60;
    private static final List<java.util.regex.Pattern> THEME_PATTERNS = List.of(
            "순환경제|재제조", "규제샌드박스|규제특례",
            "국제공동연구|국제협력|기술협력", "공연|예술|창작",
            "반도체|디스플레이|제조|설비|기계|공장|예지보전|결함탐지",
            "전산|유지보수|유지관리|^it$")
            .stream().map(java.util.regex.Pattern::compile).toList();

    static final class Features {
        final Set<String> title;
        final Set<String> purpose;
        final Set<String> terms;
        final Set<Integer> themes;

        Features(Set<String> title, Set<String> purpose) {
            this.title = Set.copyOf(title);
            this.purpose = Set.copyOf(purpose);
            var all = new HashSet<>(title);
            all.addAll(purpose);
            this.terms = Set.copyOf(all);
            this.themes = themes(terms);
        }

        int length() { return 2 * title.size() + purpose.size(); }
    }

    /** Corpus-wide statistics are immutable and shared with the cached feature snapshot. */
    record Statistics(Map<String, Integer> frequency, int count, long totalLength, Features excluded) {
        static Statistics of(List<Features> features) {
            var frequency = new HashMap<String, Integer>();
            long length = 0;
            for (Features item : features) {
                for (String term : item.terms) frequency.merge(term, 1, Integer::sum);
                length += item.length();
            }
            return new Statistics(Map.copyOf(frequency), features.size(), length, null);
        }

        Statistics excluding(Features item) { return new Statistics(frequency, count, totalLength, item); }

        double averageLength() {
            return Math.max(1, (double) (totalLength - (excluded == null ? 0 : excluded.length()))
                    / Math.max(1, count - (excluded == null ? 0 : 1)));
        }

        double idf(String term) {
            int n = count - (excluded == null ? 0 : 1);
            int df = frequency.getOrDefault(term, 0) - (excluded != null && excluded.terms.contains(term) ? 1 : 0);
            return Math.log(1 + (n - df + 0.5) / (df + 0.5));
        }
    }

    record Candidate(long id, double semantic, Features features, List<String> sharedTopics,
                     boolean reliableSemanticInput) {
        Candidate(long id, double semantic, Set<String> title, Set<String> purpose, List<String> sharedTopics) {
            this(id, semantic, new Features(title, purpose), sharedTopics, true);
        }
    }
    record Match(long id, double rankScore, String basis) {}
    private record Score(Candidate candidate, double lexical) {}

    static List<Match> rank(Set<String> title, Set<String> purpose, List<Candidate> candidates) {
        return rank(new Features(title, purpose), candidates,
                Statistics.of(candidates.stream().map(Candidate::features).toList()));
    }

    static List<Match> rank(Features query, List<Candidate> candidates, Statistics statistics) {
        if (query.terms.isEmpty() || candidates.isEmpty()) return List.of();
        double averageLength = statistics.averageLength();
        var idf = new HashMap<String, Double>();
        for (String term : query.terms) idf.put(term, statistics.idf(term));
        List<Score> scores = new ArrayList<>(candidates.size());
        for (Candidate item : candidates) {
            double lexical = 0;
            for (String term : query.terms) {
                int tf = (item.features.title.contains(term) ? 2 : 0) + (item.features.purpose.contains(term) ? 1 : 0);
                if (tf == 0) continue;
                lexical += idf.get(term) * tf * 2.2
                        / (tf + 1.2 * (0.25 + 0.75 * item.features.length() / averageLength));
            }
            scores.add(new Score(item, lexical));
        }
        Map<Long, Integer> semanticRanks = rankWindow(scores, true);
        Map<Long, Integer> lexicalRanks = rankWindow(scores, false);
        List<Match> result = new ArrayList<>();
        Map<Long, Double> semanticScores = new HashMap<>();
        for (Score score : scores) {
            Candidate item = score.candidate;
            Features other = item.features;
            boolean semanticAvailable = Double.isFinite(item.semantic);
            boolean semanticLane = semanticRanks.containsKey(item.id);
            boolean lexicalLane = lexicalRanks.containsKey(item.id);
            if (!semanticLane && !lexicalLane) continue;
            // Every acceptance path obeys the same guard, including lexical/hybrid paths.
            if (conflictingThemes(query.themes, other.themes)) continue;
            int purposeOverlap = overlap(query.purpose, other.purpose);
            double purposeCoverage = coverage(purposeOverlap, query.purpose, other.purpose);
            int overlap = overlap(query.terms, other.terms);
            double coverage = coverage(overlap, query.terms, other.terms);
            int titleOverlap = overlap(query.title, other.title);
            int sharedTitleLength = query.title.stream().filter(other.title::contains).mapToInt(String::length).sum();
            // Recover cross-posted calls with specific matching titles and corroborating semantics.
            boolean specificTitle = titleOverlap >= 2 && sharedTitleLength >= 10
                    && coverage(titleOverlap, query.title, other.title) >= 0.8
                    && (double) titleOverlap / Math.max(query.title.size(), other.title.size()) >= 0.6;
            boolean titleMatch = lexicalLane && item.semantic >= 0.65 && specificTitle
                    && !query.purpose.isEmpty() && !other.purpose.isEmpty();
            boolean corroborated = semanticAvailable && item.semantic >= 0.78
                    && item.sharedTopics.size() >= 2 && purposeOverlap >= 1 && purposeCoverage >= 0.3;
            boolean lexicalMatch = lexicalLane && item.semantic >= 0.65
                    && coverage >= 0.6 && purposeOverlap >= 2;
            boolean semanticMatch = semanticLane && item.semantic >= 0.90 && item.reliableSemanticInput
                    && !query.purpose.isEmpty() && !other.purpose.isEmpty();
            boolean lexicalOnly = !semanticAvailable && lexicalLane && coverage >= 0.8
                    && purposeCoverage >= 0.8 && purposeOverlap >= 3;
            if (!titleMatch && !corroborated && !lexicalMatch && !semanticMatch && !lexicalOnly) continue;
            double fused = (semanticLane ? 1.0 / (RRF_K + semanticRanks.get(item.id)) : 0)
                    + (lexicalLane ? 1.0 / (RRF_K + lexicalRanks.get(item.id)) : 0);
            String basis = lexicalOnly ? "LEXICAL_ONLY" : corroborated ? "HYBRID"
                    : titleMatch || lexicalMatch ? "LEXICAL" : "SEMANTIC";
            result.add(new Match(item.id, fused, basis));
            semanticScores.put(item.id, semanticAvailable ? item.semantic : -1.0);
        }
        return result.stream().sorted(Comparator.comparingDouble(Match::rankScore).reversed()
                .thenComparing(Comparator.comparingDouble((Match m) -> semanticScores.get(m.id)).reversed())
                .thenComparingLong(Match::id)).toList();
    }

    private static int overlap(Set<String> left, Set<String> right) {
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = left.size() <= right.size() ? right : left;
        int count = 0;
        for (String term : smaller) if (larger.contains(term)) count++;
        return count;
    }

    private static double coverage(int overlap, Set<String> left, Set<String> right) {
        return (double) overlap / Math.max(1, Math.min(left.size(), right.size()));
    }

    private static Map<Long, Integer> rankWindow(List<Score> scores, boolean semantic) {
        Comparator<Score> order = Comparator.comparingDouble((Score s) -> semantic ? s.candidate.semantic : s.lexical)
                .reversed().thenComparingLong(s -> s.candidate.id);
        var window = new PriorityQueue<Score>(WINDOW, order.reversed());
        for (Score score : scores) {
            if (semantic ? !Double.isFinite(score.candidate.semantic) || score.candidate.semantic < 0.65 : score.lexical <= 0) continue;
            if (window.size() < WINDOW) window.add(score);
            else if (order.compare(score, window.peek()) < 0) {
                window.poll();
                window.add(score);
            }
        }
        var ordered = window.stream().sorted(order).toList();
        Map<Long, Integer> result = new HashMap<>();
        double previous = Double.NaN;
        int rank = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Score item = ordered.get(i);
            double value = semantic ? item.candidate.semantic : item.lexical;
            if (Double.compare(previous, value) != 0) rank = i + 1;
            result.put(item.candidate.id, rank);
            previous = value;
        }
        return result;
    }

    private static boolean conflictingThemes(Set<Integer> left, Set<Integer> right) {
        return !left.isEmpty() && !right.isEmpty() && left.stream().noneMatch(right::contains);
    }

    private static Set<Integer> themes(Set<String> terms) {
        Set<Integer> result = new HashSet<>();
        for (int i = 0; i < THEME_PATTERNS.size(); i++) {
            var pattern = THEME_PATTERNS.get(i);
            if (terms.stream().anyMatch(term -> pattern.matcher(term).find())) result.add(i);
        }
        return Set.copyOf(result);
    }
}
