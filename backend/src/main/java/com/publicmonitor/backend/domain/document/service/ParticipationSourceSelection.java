package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.Request;
import com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.Source;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import java.util.*;

/** Selects readable alternate exports only after the paid request identity has been established. */
final class ParticipationSourceSelection {
    private static final List<String> FORMATS = List.of("hwpx", "hwp", "docx", "xlsx", "pdf");
    private static final int RAW_BYTE_LIMIT = 8_000;
    private static final String PREFIX = "^[\\h□■○◦●•*\\-]*(?:\\d+(?:-\\d+)*[.)]?\\h*|[가-하][.)]\\h*)?\\(?\\h*";
    private static final String END = "(?=$|[\\s:：)）·ㆍ])";
    // Frozen solely for restoring the source ids of already-paid supplemental statements.
    private static final java.util.regex.Pattern COMPATIBLE_SECTION = java.util.regex.Pattern.compile(PREFIX
            + "(?:(?:신청|지원|참여|모집|입찰참가)\\h*(?:자격|대상|요건|조건)|우대(?:사항|조건|대상|기업)?|가점(?:사항|조건|항목)?)" + END);
    private static final java.util.regex.Pattern APPLICANT_SECTION = java.util.regex.Pattern.compile(PREFIX
            + "(?:사업\\h*)?(?:(?:신청|참여|모집|입찰참가)\\h*(?:자격|대상|요건|조건)|지원\\h*(?:자격|대상|요건)|우대(?:사항|조건|대상|기업)?|가점(?:사항|조건|항목)?)" + END);
    private final Set<String> fileNames;

    private ParticipationSourceSelection(Set<String> fileNames) { this.fileNames = fileNames; }

    static ParticipationSourceSelection select(ParticipationRewriteProjection.Input input) {
        var groups = new LinkedHashMap<String, Set<String>>();
        for (var sources : input.evidence().values()) for (var source : sources) {
            String name = source.attachmentName();
            if (name == null) continue;
            String key = FORMATS.contains(format(name))
                    ? "document:" + name.substring(0, name.lastIndexOf('.')) : "other:" + name;
            groups.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(name);
        }
        var selected = new HashSet<String>();
        for (var names : groups.values()) {
            if (names.stream().map(ParticipationSourceSelection::format).distinct().count() < 2) {
                selected.addAll(names);
                continue;
            }
            String preferred = names.stream().filter(name -> input.evidence().values().stream().flatMap(List::stream)
                            .anyMatch(source -> name.equals(source.attachmentName()) && usable(source)))
                    .min(Comparator.comparingInt(name -> FORMATS.indexOf(format(name)))).orElse(null);
            if (preferred == null) {
                selected.addAll(names);
                continue;
            }
            String format = format(preferred);
            names.stream().filter(name -> format(name).equals(format)).forEach(selected::add);
        }
        return new ParticipationSourceSelection(Set.copyOf(selected));
    }

    boolean includes(RequirementSource source) {
        return source.attachmentName() == null || fileNames.contains(source.attachmentName());
    }

    Request outbound(ParticipationRewriteProjection.Input input) {
        var frozenIds = new HashSet<>(input.request().sources().stream().map(Source::id).toList());
        var sources = input.availableSources().stream().filter(source ->
                input.evidence().get(source.id()).stream().anyMatch(this::includes))
                .filter(source -> frozenIds.contains(source.id()) || input.evidence().get(source.id()).stream()
                        .filter(this::includes).anyMatch(ParticipationSourceSelection::explicit))
                .map(source -> {
                    var original = input.evidence().get(source.id()).stream().filter(this::includes).findFirst().orElseThrow();
                    String heading = withoutInferredContext(source.groupLabel());
                    String context = input.scopes() == null ? null : input.scopes().displayScope(List.of(original), original.excerpt());
                    if (context != null && !context.isBlank() && !context.equals("공통")) {
                        heading = heading == null ? "" : heading.replaceAll("(?m)^\\[상위 구역] [^\\r\\n]*(?:\\R|$)", "");
                        heading = "[상위 구역] " + context + "\n" + heading;
                    }
                    return new Source(source.id(), original.excerpt(), heading, source.criteria());
                })
                .toList();
        return new Request(sources, input.request().criteria(), input.request().businessTypes());
    }

    static boolean compatibleCandidate(RequirementSource source) {
        return COMPATIBLE_SECTION.matcher(originalHeading(source)).find()
                || source.excerpt().lines().anyMatch(line -> COMPATIBLE_SECTION.matcher(line.strip()).find());
    }

    static boolean explicit(RequirementSource source) {
        // A funding/period section named "지원 조건" is not an applicant eligibility heading.
        // Mixed tables remain candidates when an actual applicant/target column is present.
        return APPLICANT_SECTION.matcher(originalHeading(source)).find()
                || source.excerpt().lines().flatMap(line -> Arrays.stream(line.split("\\t")))
                        .anyMatch(cell -> APPLICANT_SECTION.matcher(cell.strip()).find());
    }

    private static String originalHeading(RequirementSource source) {
        String heading = Objects.toString(source.sectionTitle(), "");
        int original = heading.indexOf("[원문 구역] ");
        return (original >= 0 ? heading.substring(original + "[원문 구역] ".length()) : heading).strip();
    }

    private static boolean usable(RequirementSource source) {
        if (!explicit(source) || source.excerpt().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > RAW_BYTE_LIMIT) return false;
        return source.excerpt().lines().map(String::strip).filter(line -> !line.isEmpty()).anyMatch(line -> {
            var heading = APPLICANT_SECTION.matcher(line);
            if (!heading.find()) return true;
            String tail = line.substring(heading.end()).replaceFirst("^[\\h:：)）]+", "");
            return !tail.isBlank() && !tail.matches("^(?:및|과|[·ㆍ]).*");
        });
    }

    private static String withoutInferredContext(String heading) {
        return heading == null ? null : heading.replaceAll("(?m)^\\[(?:적용 유형|별도 참여 역할)] [^\\r\\n]*(?:\\R|$)", "");
    }

    private static String format(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
