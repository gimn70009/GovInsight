package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

/** The sole source of display text here is a validated LLM statement. */
final class ParticipationRewriteProjection {
    record Input(Request request, Map<String, List<RequirementSource>> evidence, boolean incomplete) {}

    static Input input(ParticipationGuide raw, boolean incomplete) {
        var evidence = new LinkedHashMap<String, List<RequirementSource>>();
        var identities = new LinkedHashMap<String, String>();
        for (var rule : raw.rules()) for (var source : rule.sources()) {
            if (source.excerpt() == null || source.excerpt().isBlank()) continue;
            // The same paragraph often exists in both HWP and PDF. Rewrite once, retain both citations.
            String identity = compact(Objects.toString(source.sectionTitle(), "")) + ":" + compact(source.excerpt());
            String id = identities.computeIfAbsent(identity, ignored -> "s" + identities.size());
            var originals = evidence.computeIfAbsent(id, ignored -> new ArrayList<>());
            if (!originals.contains(source)) originals.add(source);
        }
        var sources = evidence.entrySet().stream().map(item -> {
            var source = item.getValue().getFirst();
            return new Source(item.getKey(), source.excerpt(), Objects.toString(source.sectionTitle(), ""),
                    Arrays.stream(ParticipationCriterion.values()).filter(c -> c.topic.matcher(source.excerpt()).find())
                            .map(Enum::name).toList());
        }).toList();
        var catalog = Arrays.stream(ParticipationCriterion.values())
                .map(c -> new Criterion(c.name(), c.label, c.groupLabel)).toList();
        return new Input(new Request(sources, catalog, raw.variants().stream().map(ParticipationVariant::name).toList()), evidence, incomplete);
    }

    static ParticipationGuide guide(ParticipationGuide raw, Input input, Response result, String status) {
        var entries = new LinkedHashMap<String, List<ParticipationCriterionEntry>>();
        var covered = new HashSet<String>();
        if ("READY".equals(result.status()) && result.irrelevantSourceIds() != null)
            covered.addAll(result.irrelevantSourceIds());
        var unresolved = new LinkedHashSet<>(result.evidenceOnlyIds());
        var seen = new HashSet<String>();
        if ("READY".equals(result.status())) for (var item : result.statements()) {
            List<RequirementSource> sources = item.sourceIds() == null ? List.of() : item.sourceIds().stream()
                    .map(input.evidence()::get).filter(Objects::nonNull).flatMap(List::stream).distinct().toList();
            boolean criterion = Arrays.stream(ParticipationCriterion.values()).anyMatch(c -> c.name().equals(item.criterion()));
            if (!criterion || sources.isEmpty() || item.sourceIds().stream().anyMatch(id -> !input.evidence().containsKey(id))
                    || item.text() == null || item.text().isBlank() || item.supportingQuotes() == null
                    || item.supportingQuotes().isEmpty() || item.supportingQuotes().stream().anyMatch(quote ->
                        quote == null || quote.isBlank() || sources.stream().noneMatch(source -> compact(source.excerpt()).contains(compact(quote))))) {
                if (item.sourceIds() != null) unresolved.addAll(item.sourceIds());
                continue;
            }
            if (!seen.add(compact(item.text()))) { unresolved.addAll(item.sourceIds()); continue; }
            var c = ParticipationCriterion.valueOf(item.criterion());
            var rule = new ParticipationRule(item.kind(), c.label, item.text(), null, null,
                    List.of(), List.of(), List.of(), List.of(), sources);
            entries.computeIfAbsent(c.name(), ignored -> new ArrayList<>()).add(
                    new ParticipationCriterionEntry(rule, null, null, List.of(), "VERIFIED"));
            covered.addAll(item.sourceIds());
        }
        for (var source : input.request().sources()) {
            if (covered.contains(source.id()) && !unresolved.contains(source.id())) continue;
            // Unknown classification remains under OTHER with evidence, never a fabricated sentence.
            var codes = source.criteria().isEmpty() ? List.of("OTHER") : source.criteria();
            for (String code : codes) {
                String label = "OTHER".equals(code) ? "그 밖의 명시 조건" : ParticipationCriterion.valueOf(code).label;
                var rule = new ParticipationRule("EVIDENCE", label, "", null, null, List.of(), List.of(),
                        List.of(), List.of(), input.evidence().get(source.id()));
                entries.computeIfAbsent(code, ignored -> new ArrayList<>()).add(
                        new ParticipationCriterionEntry(rule, null, null, List.of(), "EVIDENCE_ONLY"));
            }
        }
        boolean unreviewed = input.request().sources().stream().anyMatch(source -> !covered.contains(source.id()) || unresolved.contains(source.id()));
        var groups = new LinkedHashMap<String, List<ParticipationCriterionRow>>();
        var labels = new LinkedHashMap<String, String>();
        for (var c : ParticipationCriterion.values()) {
            var items = entries.getOrDefault(c.name(), List.of());
            boolean verified = items.stream().anyMatch(e -> "VERIFIED".equals(e.displayStatus()));
            String rowStatus = verified ? "STATED" : !items.isEmpty() || input.incomplete() || unreviewed || !"READY".equals(status)
                    ? "REVIEW_REQUIRED" : "NOT_STATED";
            groups.computeIfAbsent(c.group, ignored -> new ArrayList<>()).add(new ParticipationCriterionRow(c.name(), c.label, rowStatus, items));
            labels.put(c.group, c.groupLabel);
        }
        if (entries.containsKey("OTHER")) groups.get("COMPANY").add(new ParticipationCriterionRow(
                "OTHER", "그 밖의 명시 조건", "REVIEW_REQUIRED", entries.get("OTHER")));
        var categories = groups.entrySet().stream().map(group -> new ParticipationCategory(group.getKey(), labels.get(group.getKey()), group.getValue())).toList();
        return new ParticipationGuide(raw.conditions(), raw.rules(), raw.variants(), categories, status);
    }
    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
}
