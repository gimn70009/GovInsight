package com.publicmonitor.backend.domain.analysis.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Persistent review metadata lives alongside the findings in comparison_summary. */
public final class LegalReviewPolicy {
    public static final int VERSION = 2;
    public static final List<String> TYPES = List.of("DUPLICATE_SUPPORT", "COST_DOUBLE_COUNTING",
            "RESULT_IP_REUSE", "CONFIDENTIALITY", "PROPOSAL_TEXT_REUSE");
    private static final Set<String> COMPLETED = Set.of("RESTRICTION_FOUND", "CAUTION", "NOT_FOUND", "DATA_INSUFFICIENT");
    private static final Set<String> NEEDS_SOURCE = Set.of("CONTEXT_LIMIT", "CONTEXT_REQUIRED");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LegalReviewPolicy() {}

    public static List<String> pendingTypes(String summary, Instant now) {
        try {
            if (summary == null || summary.isBlank()) return TYPES;
            JsonNode root = MAPPER.readTree(summary);
            if (root.path("legalReviewVersion").asInt(0) != VERSION) return TYPES;
            // Repeated provider/format failures have bounded retries and a cooldown.
            if (root.path("legalReviewAttempts").asInt(0) >= 3) return List.of();
            String reviewedAt = root.path("legalReviewedAt").asText("");
            if (!reviewedAt.isBlank() && Instant.parse(reviewedAt).plusSeconds(1800).isAfter(now)) {
                return List.of();
            }
            JsonNode risks = root.path("legalRisks");
            List<String> pending = new ArrayList<>();
            for (String type : TYPES) {
                List<JsonNode> matches = new ArrayList<>();
                if (risks.isArray()) for (JsonNode risk : risks) {
                    if (type.equals(risk.path("type").asText())) matches.add(risk);
                }
                if (matches.size() != 1) { pending.add(type); continue; }
                JsonNode risk = matches.getFirst();
                String reason = risk.path("failureReason").asText("");
                if (NEEDS_SOURCE.contains(reason)) continue;
                if (!COMPLETED.contains(risk.path("status").asText())
                        || risk.path("summary").asText("").isBlank()
                        || java.util.Set.of("INTERPRETATION_MISSING", "UNSUPPORTED_INTERPRETATION").contains(reason)) pending.add(type);
            }
            return List.copyOf(pending);
        } catch (RuntimeException exception) {
            return TYPES;
        }
    }
}
