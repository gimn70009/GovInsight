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
            if (valid(item, input.evidence())) {
                var details = item.details().stream().filter(detail -> validDetail(detail, input.evidence())).limit(6).toList();
                grounded.add(new Statement(item.sourceIds(), item.criterion(), item.text(), item.groupLabel(),
                        item.supportingQuotes(), item.kind(), item.scope(), item.audience(), details, item.reviewRequired()));
            }
            else if (item != null && item.sourceIds() != null) unresolved.addAll(item.sourceIds());
        }
        var displayItems = ParticipationDisplayStatements.prepare(input, grounded);
        var knownPrograms = displayItems.stream().map(item -> displayScope(item.scope(), null))
                .filter(Objects::nonNull).flatMap(value -> Arrays.stream(value.split(" / "))).distinct().toList();
        for (var item : displayItems) {
            // Paid statements retain every grounded citation; export selection must not trim their meaning.
            var sourceIds = new LinkedHashSet<>(item.sourceIds());
            var allQuotes = new LinkedHashSet<>(item.supportingQuotes());
            for (var detail : item.details()) {
                sourceIds.addAll(detail.sourceIds());
                allQuotes.addAll(detail.supportingQuotes());
                for (String id : detail.sourceIds()) displayedQuotes.computeIfAbsent(id, ignored -> new ArrayList<>()).addAll(detail.supportingQuotes());
            }
            var sources = sourceIds.stream().map(input.evidence()::get).flatMap(List::stream).distinct().toList();
            var quotes = List.copyOf(allQuotes);
            var criterion = ParticipationFocusCriterion.valueOf(item.criterion());
            var mainSources = item.sourceIds().stream().map(input.evidence()::get).flatMap(List::stream).distinct().toList();
            String applicant = ParticipationApplicantLabel.from(item, mainSources);
            String audience = ParticipationApplicantLabel.isPersonnelAudience(item, mainSources) ? null
                    : item.audience() == null || item.audience().isBlank() ? applicant : item.audience();
            boolean procedure = ParticipationPresentationQuality.procedureOnly(item, mainSources);
            boolean review = item.reviewRequired() || !ParticipationPresentationQuality.completeSentence(item.text());
            var readableDetails = item.details().stream()
                    .filter(detail -> !ParticipationPresentationQuality.sameText(item.text(), detail.text()))
                    .filter(detail -> !detail.reviewRequired() && ParticipationPresentationQuality.completeSentence(detail.text()))
                    .map(Detail::text).distinct().toList();
            boolean detailReview = item.details().stream()
                    .filter(detail -> !ParticipationPresentationQuality.sameText(item.text(), detail.text()))
                    .anyMatch(detail -> detail.reviewRequired() || !ParticipationPresentationQuality.completeSentence(detail.text()));
            var rule = new ParticipationRule(procedure ? "APPLICATION_GUIDANCE"
                    : "EXCLUSION".equals(item.kind()) ? "EXCLUSION"
                    : "PREFERENCE".equals(item.kind()) ? "PREFERENCE" : "BASIC",
                    criterion.label, item.text(), ParticipationNamedPrograms.expand(displayScope(ParticipationProgramScope.resolve(
                            item.scope(), item, mainSources, currentInput.scopes()), applicant), knownPrograms), audience,
                    readableDetails, List.of(), detailReview ? List.of(ParticipationPresentationQuality.DETAIL_REVIEW) : List.of(), List.of(), sources);
            entries.computeIfAbsent(criterion.name(), ignored -> new ArrayList<>()).add(
                    new ParticipationCriterionEntry(rule, null, null, List.of(), review ? "REVIEW_REQUIRED" : "VERIFIED", quotes));
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
        // Old READY caches may contain only rejected/unresolved evidence. Do not report absence.
        String displayStatus = "READY".equals(status) && displayedRules.isEmpty() && !unresolved.isEmpty()
                ? "UNAVAILABLE" : status;
        return new ParticipationGuide(List.of(), displayedRules, raw.variants(),
                List.of(new ParticipationCategory("COMPANY_REQUIREMENTS", "참여기업 조건", rows)), displayStatus);
    }

    private static boolean valid(Statement item, Map<String, List<RequirementSource>> evidence) {
        if (item == null || !ParticipationFocusCriterion.supports(item.criterion())
                || !("ELIGIBILITY".equals(item.kind()) || "EXCLUSION".equals(item.kind()) || "PREFERENCE".equals(item.kind()))
                || item.text() == null || item.text().isBlank() || item.sourceIds() == null || item.sourceIds().isEmpty()
                || item.sourceIds().stream().anyMatch(id -> !evidence.containsKey(id))
                || item.supportingQuotes() == null || item.supportingQuotes().isEmpty()
                || item.supportingQuotes().stream().anyMatch(quote -> quote == null || quote.isBlank())) return false;
        var sources = item.sourceIds().stream().map(evidence::get).flatMap(List::stream).toList();
        return item.supportingQuotes().stream().allMatch(quote -> sources.stream().anyMatch(source -> source.excerpt().contains(quote)))
                && item.sourceIds().stream().allMatch(id -> evidence.get(id).stream().anyMatch(source ->
                        item.supportingQuotes().stream().anyMatch(quote -> source.excerpt().contains(quote))));
    }

    private static boolean validDetail(Detail detail, Map<String, List<RequirementSource>> evidence) {
        if (detail == null || detail.text() == null || detail.text().isBlank() || detail.text().strip().matches("\\[\\s*\\]|null") || detail.text().length() > 240
                || detail.supportingQuotes() == null || detail.supportingQuotes().size() > 4
                || detail.supportingQuotes().stream().anyMatch(quote -> quote == null || quote.length() > 600)) return false;
        // Validate each explanation independently; a bad explanation cannot erase a grounded parent.
        return valid(new Statement(detail.sourceIds(), "BUSINESS_FORM", detail.text(), "",
                detail.supportingQuotes(), "ELIGIBILITY"), evidence);
    }

    private static String displayScope(String value, String applicant) {
        if (value == null || value.isBlank()) return null;
        var paths = new LinkedHashSet<String>();
        for (String path : value.split(" / ")) {
            var parts = Arrays.stream(path.split(" · ")).map(String::strip)
                    .map(part -> "common".equalsIgnoreCase(part) ? "공통" : part)
                    .filter(part -> !genericScopeLabel(part))
                    .filter(part -> applicant == null || !compact(part).equals(compact(applicant))).toList();
            if (!parts.isEmpty()) paths.add(String.join(" · ", parts));
        }
        return paths.isEmpty() ? null : String.join(" / ", paths);
    }

    private static boolean genericScopeLabel(String value) {
        String compact = compact(value);
        // Compare headings without numbering, but preserve the original spelling of real programs.
        String heading = compact.replaceFirst("^(?:\\(?(?:[0-9]+(?:-[0-9]+)*|[가-힣]|[IVXⅠ-Ⅻ]+)[.)]|[①-⑳])", "");
        return compact.isEmpty() || compact.matches("(?:별표|별지|붙임)[0-9]+(?:호)?")
                || compact.matches(".*(?:법률|시행령|시행규칙|운영요령|규정)제[0-9]+조(?:의[0-9]+)?(?:제[0-9]+항)?") || Set.of("상위구역", "원문구역", "적용유형", "유형명", "신청역할", "신청주체명",
                "신청자격", "신청요건", "신청자격및신청요건", "지원대상", "지원내용", "사업개요",
                "scope", "parent", "section", "program", "null").contains(heading.toLowerCase(Locale.ROOT))
                || compact.matches("^\\[(?:상위구역|원문구역|적용유형)\\].*")
                || compact.matches("실제사업[·ㆍ]과제유형(?:\\(.*\\))?")
                || heading.matches("(?:(?:세부|내역)?(?:사업|과제))?(?:지원)?(?:유형|구분)"
                        + "|(?:사업|유형)별(?:지원계획|지원내용|신청자격)|신청자격및선정기준|유형미지정");
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
