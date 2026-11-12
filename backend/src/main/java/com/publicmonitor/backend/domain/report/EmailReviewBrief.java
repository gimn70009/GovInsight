package com.publicmonitor.backend.domain.report;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Only saved company analysis is sent; deadline arithmetic remains in Java. */
public final class EmailReviewBrief {
    private EmailReviewBrief() {}

    public record Source(String id, String text) {}
    public record Document(long id, List<Source> sources) {}
    public record Request(List<Document> documents) {}
    public record Response(List<Result> documents) {}
    public record Result(long id, String reviewReason, List<String> priorityChecks) {
        public boolean valid() {
            return (reviewReason == null || validText(reviewReason, 160))
                    && priorityChecks != null && priorityChecks.size() <= 2
                    && priorityChecks.stream().allMatch(text -> validText(text, 80));
        }
        public Result sanitized() {
            String reason = validText(reviewReason, 160) ? reviewReason : null;
            var checks = priorityChecks == null ? List.<String>of() : priorityChecks.stream()
                    .filter(text -> validText(text, 80)).distinct().limit(2).toList();
            return new Result(id, reason, checks);
        }
        private static boolean validText(String text, int limit) {
            return EmailReviewText.complete(text, limit, limit == 160 ? 2 : 1);
        }
    }

    public static Document source(long id, DocumentDetectionDetailResponse detail) {
        var analysis = detail.analysis();
        if (analysis == null || (analysis.proposal() != null
                && Boolean.TRUE.equals(analysis.proposal().usesDemoProfile()))) return null;
        var sources = new ArrayList<Source>();
        if (analysis.opportunity() != null && analysis.opportunity().dimensions() != null) {
            for (var dimension : analysis.opportunity().dimensions()) {
                if (dimension == null || dimension.type() == null) continue;
                String key = switch (dimension.type()) {
                    case COMPANY_FIT -> "company_fit";
                    case BUSINESS_VALUE -> "business_value";
                    case FEASIBILITY -> "feasibility";
                    default -> null;
                };
                if (key != null) add(sources, key, dimension.reason());
            }
        }
        var preparation = analysis.proposal() == null ? null : analysis.proposal().preparation();
        if (preparation != null && preparation.strategy() != null) {
            var strategy = preparation.strategy();
            add(sources, "decision", strategy.decisionReason());
            if (strategy.criticalGaps() != null) {
                int index = 0;
                for (var gap : strategy.criticalGaps()) {
                    if (index >= 6) break;
                    if (gap instanceof Map<?, ?> fields) {
                        add(sources, "gap-" + index++, join(fields.get("gap"), fields.get("nextAction")));
                    } else if (gap instanceof DocumentDetectionDetailResponse.StrategyGap item) {
                        add(sources, "gap-" + index++, join(item.gap(), item.nextAction()));
                    }
                }
            }
        }
        if (preparation != null && preparation.eligibilityChecklist() != null) {
            int index = 0;
            for (var item : preparation.eligibilityChecklist()) {
                if (index >= 10) break;
                if (item != null) add(sources, "eligibility-" + index++, join(item.title(),
                        item.appliesTo(), item.requirementLevel(), item.stage(), item.detail(), item.nextAction()));
            }
        }
        return sources.isEmpty() ? null : new Document(id, List.copyOf(sources));
    }

    private static void add(List<Source> sources, String id, String text) {
        // Never cut a condition midway to fit the input budget.
        if (text == null || text.isBlank() || text.length() > 6000
                || sources.stream().anyMatch(source -> source.id().equals(id))
                || sources.stream().mapToInt(source -> source.text().length()).sum() + text.length() > 20000) return;
        sources.add(new Source(id, text.strip()));
    }

    private static String join(Object... parts) {
        return java.util.Arrays.stream(parts).filter(value -> value instanceof String)
                .map(Object::toString).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.joining(" | "));
    }
}
