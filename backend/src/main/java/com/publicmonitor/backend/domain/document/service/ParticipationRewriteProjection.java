package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

/** Only source quotations classified into the supported eligibility fields reach the table. */
final class ParticipationRewriteProjection {
    record Input(Request request, Map<String, List<RequirementSource>> evidence, boolean incomplete, ParticipationScopeIndex scopes) {}

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
        return new Input(new Request(sources, catalog, raw.variants().stream().map(ParticipationVariant::name).toList()), evidence, incomplete, scopes);
    }

    static ParticipationGuide guide(ParticipationGuide raw, Input input, Response result, String status) {
        var localConditions = ParticipationLocalConditions.extract(input);
        var entries = new LinkedHashMap<String, List<ParticipationCriterionEntry>>();
        var covered = new HashSet<String>();
        var irrelevant = new HashSet<String>();
        if ("READY".equals(result.status()) && result.irrelevantSourceIds() != null)
            irrelevant.addAll(result.irrelevantSourceIds());
        var unresolved = new LinkedHashSet<>(result.evidenceOnlyIds());
        var verifiedRules = new LinkedHashMap<String, Verified>();
        var displayedRules = new ArrayList<ParticipationRule>();
        if ("READY".equals(result.status())) for (var item : result.statements()) {
            List<RequirementSource> sources = item.sourceIds() == null ? List.of() : item.sourceIds().stream()
                    .map(input.evidence()::get).filter(Objects::nonNull).flatMap(List::stream).distinct().toList();
            final var quoteSources = sources;
            boolean criterion = ParticipationFocusCriterion.supports(item.criterion());
            boolean kind = "ELIGIBILITY".equals(item.kind()) || "PREFERENCE".equals(item.kind());
            if (!criterion || !kind || sources.isEmpty() || item.sourceIds().stream().anyMatch(id -> !input.evidence().containsKey(id))
                    || item.text() == null || item.text().isBlank() || item.supportingQuotes() == null
                    || item.supportingQuotes().isEmpty() || item.supportingQuotes().stream().anyMatch(quote ->
                        quote == null || quote.isBlank() || quoteSources.stream().noneMatch(source -> source.excerpt().contains(quote)))) {
                if (item.sourceIds() != null) unresolved.addAll(item.sourceIds());
                continue;
            }
            String quote = item.supportingQuotes().getFirst();
            var directSources = sources.stream().filter(source -> source.excerpt().contains(quote)).toList();
            if (localConditions.stream().anyMatch(local -> ParticipationLocalConditions.covers(local, directSources, quote))) continue;
            var display = directSources.stream()
                    .map(source -> ParticipationFocusedText.prepare(item.criterion(), item.text(), quote, source.sectionTitle(), item.kind()))
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (display == null || (input.scopes() != null && ParticipationAudience.recipient(directSources, input.scopes().context(directSources)))) {
                unresolved.addAll(item.sourceIds());
                continue;
            }
            String identity = item.criterion() + ":" + item.kind() + ":" + compact(display.text());
            var previous = verifiedRules.get(identity);
            if (previous != null) {
                var combined = new LinkedHashSet<>(previous.rule().sources());
                combined.addAll(sources);
                sources = List.copyOf(combined);
            }
            var c = ParticipationFocusCriterion.valueOf(item.criterion());
            String section = "PREFERENCE".equals(item.kind()) ? "PREFERENCE" : "BASIC";
            String scope = input.scopes() == null ? displayScope(sources, display.quote()) : input.scopes().scope(sources, display.quote());
            scope = ParticipationLocalConditions.cleanScope(scope);
            var rule = new ParticipationRule(section, c.label, display.text(), scope, null,
                    List.of(), List.of(), List.of(), List.of(), sources);
            verifiedRules.put(identity, new Verified(c, rule, display.quote()));
            item.sourceIds().forEach(id -> covered.add(c.name() + ":" + id));
        }
        for (var item : verifiedRules.values()) {
            entries.computeIfAbsent(item.criterion().name(), ignored -> new ArrayList<>()).add(
                    new ParticipationCriterionEntry(item.rule(), null, null, List.of(), "VERIFIED", List.of(item.quote())));
            displayedRules.add(item.rule());
        }
        for (var local : localConditions) {
            entries.computeIfAbsent(local.code(), ignored -> new ArrayList<>()).add(
                    new ParticipationCriterionEntry(local.rule(), null, null, List.of(), "VERIFIED", List.of(local.quote())));
            displayedRules.add(local.rule());
        }
        for (var source : input.request().sources()) {
            for (String code : source.criteria()) {
                if (!ParticipationFocusCriterion.supports(code)) continue;
                if ((covered.contains(code + ":" + source.id()) || irrelevant.contains(source.id()))
                        && !unresolved.contains(source.id())) continue;
                var rule = new ParticipationRule("EVIDENCE", ParticipationFocusCriterion.valueOf(code).label, "", null, null,
                        List.of(), List.of(), List.of(), List.of(), input.evidence().get(source.id()));
                entries.computeIfAbsent(code, ignored -> new ArrayList<>()).add(
                        new ParticipationCriterionEntry(rule, null, null, List.of(), "EVIDENCE_ONLY"));
            }
        }
        var rows = Arrays.stream(ParticipationFocusCriterion.values()).map(c -> {
            var items = entries.getOrDefault(c.name(), List.of());
            boolean verified = items.stream().anyMatch(e -> "VERIFIED".equals(e.displayStatus()));
            // A bounded extraction cannot establish that an entire notice has no condition for a field.
            return new ParticipationCriterionRow(c.name(), c.label, verified ? "STATED" : "REVIEW_REQUIRED", items);
        }).toList();
        var category = new ParticipationCategory("COMPANY_REQUIREMENTS", "참여기업 조건", rows);
        return new ParticipationGuide(List.of(), displayedRules, raw.variants(), List.of(category), status);
    }
    private static List<String> matching(RequirementSource source, boolean legacy, boolean previous) {
        return legacy ? ParticipationLegacyCriteria.matching(source.excerpt(), source.sectionTitle())
                : ParticipationFocusCriterion.matching(source.excerpt(), source.sectionTitle(), previous);
    }
    private record Verified(ParticipationFocusCriterion criterion, ParticipationRule rule, String quote) {}

    private static String displayScope(List<RequirementSource> sources, String text) {
        var headings = new LinkedHashSet<String>();
        for (var source : sources) {
            String heading = Objects.toString(source.sectionTitle(), "");
            int section = heading.indexOf("[원문 구역] ");
            if (section >= 0) heading = heading.substring(section + "[원문 구역] ".length());
            heading = heading.strip().replaceFirst("^(?:[□■○◦●•]|[0-9]+[.)])\\s*", "");
            heading = heading.replaceFirst("^(?:신청|지원|참여|모집)\\s*(?:자격|대상|요건|조건)\\s*[:：]\\s*", "");
            String key = compact(heading);
            if (key.isEmpty() || compact(text).contains(key) || ParticipationScopeIndex.generic(heading)
                    || key.matches("(?:(?:신청|지원|참여|모집)(?:자격|대상|요건|조건)|필수(?:요건|조건)|우대(?:사항|조건|항목)?|가점(?:사항|항목)?)")) continue;
            headings.add(heading);
        }
        return headings.isEmpty() ? null : String.join(" / ", headings);
    }
    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
}
