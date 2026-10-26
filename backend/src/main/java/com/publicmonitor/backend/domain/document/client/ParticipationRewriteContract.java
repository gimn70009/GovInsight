package com.publicmonitor.backend.domain.document.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ParticipationRewriteContract {
    private ParticipationRewriteContract() {}
    public record Criterion(String code, String label, String category) {}
    public record Source(String id, String excerpt, @JsonProperty("group_label") String groupLabel,
                         List<String> criteria) {}
    public record Request(List<Source> sources, List<Criterion> criteria,
                          @JsonProperty("business_types") List<String> businessTypes) {}
    public record Statement(@JsonProperty("source_ids") List<String> sourceIds, String criterion,
                            String text, @JsonProperty("group_label") String groupLabel,
                            @JsonProperty("supporting_quotes") List<String> supportingQuotes, String kind) {}
    public record Response(List<Statement> statements,
                           @JsonProperty("evidence_only_ids") List<String> evidenceOnlyIds, String status,
                           @JsonProperty("irrelevant_source_ids") List<String> irrelevantSourceIds) {
        public Response(List<Statement> statements, List<String> evidenceOnlyIds, String status) {
            this(statements, evidenceOnlyIds, status, List.of());
        }
        public static Response unavailable(Request request) {
            return new Response(List.of(), request.sources().stream().map(Source::id).toList(), "UNAVAILABLE");
        }
    }
}
