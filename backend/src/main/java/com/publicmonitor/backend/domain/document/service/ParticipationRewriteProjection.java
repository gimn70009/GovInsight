package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

/** Only source quotations classified into the supported eligibility fields reach the table. */
final class ParticipationRewriteProjection {
    record Input(Request request, Map<String, List<RequirementSource>> evidence, boolean incomplete,
                 ParticipationScopeIndex scopes, List<Source> availableSources) {}

    static Input input(ParticipationGuide raw, boolean incomplete) {
        return input(raw, incomplete, null);
    }

    static Input input(ParticipationGuide raw, boolean incomplete, ParticipationScopeIndex scopes) {
        return input(raw, incomplete, scopes, false, false);
    }

    static Input legacyInput(ParticipationGuide raw, boolean incomplete, ParticipationScopeIndex scopes) {
        return input(raw, incomplete, scopes, true, true);
    }

    static Input previousInput(ParticipationGuide raw, boolean incomplete, ParticipationScopeIndex scopes) {
        return input(raw, incomplete, scopes, false, true);
    }

    private static Input input(ParticipationGuide raw, boolean incomplete, ParticipationScopeIndex scopes, boolean legacy, boolean previous) {
        var evidence = new LinkedHashMap<String, List<RequirementSource>>();
        var identities = new LinkedHashMap<String, String>();
        for (var rule : raw.rules()) for (var source : rule.sources()) {
            if (source.excerpt() == null || source.excerpt().isBlank()
                    || matching(source, legacy, previous).isEmpty()) continue;
            // The same paragraph often exists in both HWP and PDF. Classify once, retain both citations.
            String identity = compact(Objects.toString(source.sectionTitle(), "")) + ":" + compact(source.excerpt());
            if (!previous && scopes != null)
                identity += ":" + Objects.toString(scopes.scope(List.of(source), source.excerpt()), "")
                        + ":" + scopes.context(List.of(source));
            String id = identities.computeIfAbsent(identity, ignored -> "s" + identities.size());
            var originals = evidence.computeIfAbsent(id, ignored -> new ArrayList<>());
            if (!originals.contains(source)) originals.add(source);
        }
        var sources = evidence.entrySet().stream().map(item -> {
            var source = item.getValue().getFirst();
            String heading = Objects.toString(source.sectionTitle(), "");
            if (!previous && scopes != null) {
                String parent = scopes.context(List.of(source));
                String scope = scopes.scope(List.of(source), source.excerpt().lines().findFirst().orElse(""));
                heading = (parent.isBlank() ? "" : "[상위 구역] " + parent + "\n")
                        + (scope == null ? "" : "[적용 유형] " + scope + "\n") + heading;
            }
            return new Source(item.getKey(), source.excerpt(), heading,
                    matching(source, legacy, previous));
        }).toList();
        var catalog = legacy
                ? Arrays.stream(ParticipationLegacyCriteria.values()).map(c -> new Criterion(c.name(), c.label, "참여기업 조건")).toList()
                : Arrays.stream(ParticipationFocusCriterion.values()).map(c -> new Criterion(c.name(), c.label, "참여기업 조건")).toList();
        // The frozen request determines paid-cache identity. Explicit sections outside its old vocabulary
        // are offered only to new requests, with content-stable ids that cannot shift existing s ids.
        var available = new ArrayList<>(sources);
        if (!legacy && !previous) {
            var known = new HashSet<RequirementSource>();
            evidence.values().forEach(known::addAll);
            for (var rule : raw.rules()) for (var source : rule.sources()) {
                if (known.contains(source) || source.excerpt() == null || source.excerpt().isBlank()
                        || !(ParticipationSourceSelection.compatibleCandidate(source)
                        || ParticipationSourceSelection.explicit(source))) continue;
                String parent = scopes == null ? "" : scopes.originalContexts(List.of(source));
                String identity = compact(Objects.toString(source.sectionTitle(), "")) + ":" + compact(source.excerpt()) + ":" + parent;
                String id = supplementalId(identity);
                if (!evidence.containsKey(id)) {
                    String heading = (parent.isBlank() ? "" : "[상위 구역] " + parent + "\n")
                            + Objects.toString(source.sectionTitle(), "");
                    available.add(new Source(id, source.excerpt(), heading,
                            Arrays.stream(ParticipationFocusCriterion.values()).map(Enum::name).toList()));
                }
                var originals = evidence.computeIfAbsent(id, ignored -> new ArrayList<>());
                if (!originals.contains(source)) originals.add(source);
            }
        }
        return new Input(new Request(sources, catalog, raw.variants().stream().map(ParticipationVariant::name).toList()),
                evidence, incomplete, scopes, List.copyOf(available));
    }

    static ParticipationGuide guide(ParticipationGuide raw, Input input, Response result, String status) {
        return guide(raw, input, result, status, input);
    }

    static ParticipationGuide guide(ParticipationGuide raw, Input input, Response result, String status, Input currentInput) {
        var selected = ParticipationSourceSelection.select(currentInput);
        var entries = new LinkedHashMap<String, List<ParticipationCriterionEntry>>();
        var displayedQuotes = new LinkedHashMap<String, List<String>>();
        var unresolved = new HashSet<>(result.evidenceOnlyIds() == null ? List.<String>of() : result.evidenceOnlyIds());
        var irrelevant = "READY".equals(result.status()) && result.irrelevantSourceIds() != null
                ? new HashSet<>(result.irrelevantSourceIds()) : Set.<String>of();
        var displayedRules = new ArrayList<ParticipationRule>();
        var grounded = new ArrayList<Statement>();
        if ("READY".equals(result.status()) && result.statements() != null) for (var item : result.statements()) {
            if (valid(item, input.evidence())) grounded.add(item);
            else if (item != null && item.sourceIds() != null) unresolved.addAll(item.sourceIds());
        }
        for (var item : ParticipationDisplayStatements.prepare(input, grounded)) {
            // Paid statements retain every grounded citation; export selection must not trim their meaning.
            var sources = item.sourceIds().stream().map(input.evidence()::get).flatMap(List::stream).distinct().toList();
            var quotes = item.supportingQuotes().stream().distinct().toList();
            var criterion = ParticipationFocusCriterion.valueOf(item.criterion());
            var rule = new ParticipationRule("PREFERENCE".equals(item.kind()) ? "PREFERENCE" : "BASIC",
                    criterion.label, item.text(), item.scope(), item.audience(),
                    List.of(), List.of(), List.of(), List.of(), sources);
            entries.computeIfAbsent(criterion.name(), ignored -> new ArrayList<>()).add(
                    new ParticipationCriterionEntry(rule, null, null, List.of(), "VERIFIED", quotes));
            displayedRules.add(rule);
            for (String id : item.sourceIds()) displayedQuotes.computeIfAbsent(id, ignored -> new ArrayList<>()).addAll(item.supportingQuotes());
        }
        for (var source : currentInput.availableSources()) {
            // Legacy ids belong only to their original request, never to newly recovered raw sections.
            if (input == currentInput && !unresolved.contains(source.id())
                    && (irrelevant.contains(source.id()) || currentInput.evidence().get(source.id()).stream().allMatch(original ->
                        ParticipationDisplayStatements.fullyCovered(original, displayedQuotes.getOrDefault(source.id(), List.of()))))) continue;
            var sources = currentInput.evidence().get(source.id()).stream().filter(selected::includes).toList();
            if (sources.isEmpty()) continue;
            for (String code : source.criteria()) {
                if (!ParticipationFocusCriterion.supports(code)) continue;
                var rule = new ParticipationRule("EVIDENCE", ParticipationFocusCriterion.valueOf(code).label, "", null, null,
                        List.of(), List.of(), List.of(), List.of(), sources);
                entries.computeIfAbsent(code, ignored -> new ArrayList<>()).add(
                        new ParticipationCriterionEntry(rule, null, null, List.of(), "EVIDENCE_ONLY"));
            }
        }
        var rows = Arrays.stream(ParticipationFocusCriterion.values()).map(c -> {
            var items = entries.getOrDefault(c.name(), List.of());
            boolean verified = items.stream().anyMatch(e -> "VERIFIED".equals(e.displayStatus()));
            return new ParticipationCriterionRow(c.name(), c.label, verified ? "STATED" : "REVIEW_REQUIRED", items);
        }).toList();
        return new ParticipationGuide(List.of(), displayedRules, raw.variants(),
                List.of(new ParticipationCategory("COMPANY_REQUIREMENTS", "참여기업 조건", rows)), status);
    }

    private static boolean valid(Statement item, Map<String, List<RequirementSource>> evidence) {
        if (item == null || !ParticipationFocusCriterion.supports(item.criterion())
                || !("ELIGIBILITY".equals(item.kind()) || "PREFERENCE".equals(item.kind()))
                || item.text() == null || item.text().isBlank() || item.sourceIds() == null || item.sourceIds().isEmpty()
                || item.sourceIds().stream().anyMatch(id -> !evidence.containsKey(id))
                || item.supportingQuotes() == null || item.supportingQuotes().isEmpty()
                || item.supportingQuotes().stream().anyMatch(quote -> quote == null || quote.isBlank())) return false;
        var sources = item.sourceIds().stream().map(evidence::get).flatMap(List::stream).toList();
        return item.supportingQuotes().stream().allMatch(quote -> sources.stream().anyMatch(source -> source.excerpt().contains(quote)))
                && item.sourceIds().stream().allMatch(id -> evidence.get(id).stream().anyMatch(source ->
                        item.supportingQuotes().stream().anyMatch(quote -> source.excerpt().contains(quote))));
    }

    private static List<String> matching(RequirementSource source, boolean legacy, boolean previous) {
        return legacy ? ParticipationLegacyCriteria.matching(source.excerpt(), source.sectionTitle())
                : ParticipationFocusCriterion.matching(source.excerpt(), source.sectionTitle(), previous);
    }
    private static String supplementalId(String identity) {
        try {
            return "u" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
}
