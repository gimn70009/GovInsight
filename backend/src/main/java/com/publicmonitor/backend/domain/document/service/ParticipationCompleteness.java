package com.publicmonitor.backend.domain.document.service;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative source-structure checks shared with Python completeness.py. */
final class ParticipationCompleteness {
    private static final Pattern LAW = Pattern.compile("[「｢『]([^」｣』]+)[」｣』](?:\\s*(제\\s*\\d+\\s*조(?:의\\s*\\d+)?))?");
    private static final Pattern EFFECT = Pattern.compile("협약\\s*(?:해약|해지)|선정\\s*취소|참여\\s*제한|제재|환수");

    static String reviewReason(String text, List<String> details, List<String> quotes, String kind) {
        String evidence = String.join("\n", new LinkedHashSet<>(quotes));
        String displayed = expandLawAliases(evidence, text + " " + String.join(" ", details));
        long rows = evidence.lines().filter(line -> line.contains("\t")).distinct().count();
        if ("ELIGIBILITY".equals(kind) && rows <= 1) {
            var laws = LAW.matcher(evidence).results().map(m -> compact(m.group())).distinct().toList();
            if (laws.size() >= 2 && laws.stream().anyMatch(law -> !displayed.contains(law)))
                return "LEGAL_SCOPE_INCOMPLETE";
        }
        if ("EXCLUSION".equals(kind)) {
            var claimed = effects(text);
            if (!effects(evidence).containsAll(claimed)) return "OUTCOME_NOT_IN_CITATION";
            String[] parts = evidence.split("\\n\\s*[*※]\\s*");
            if (parts.length > 1 && text.matches("(?s).*(?:거나|또는).*")) {
                var notes = effects(String.join("\n", Arrays.copyOfRange(parts, 1, parts.length)));
                notes.removeAll(effects(parts[0]));
                notes.retainAll(claimed);
                if (!notes.isEmpty()) return "OUTCOME_SCOPE_MERGED";
            }
        }
        return null;
    }

    static String expandLawAliases(String original, String text) {
        text = text.replaceAll("[「」｢｣『』\"“”]", "");
        var names = LAW.matcher(original).results().map(m -> compact(m.group(1))).collect(java.util.stream.Collectors.toSet());
        var parents = names.stream().map(name -> name.replaceFirst("(?:시행령|시행규칙)$", "")).distinct().toList();
        String parentPattern = parents.size() == 1 ? Arrays.stream(parents.getFirst().split(""))
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("\\s*")) : "";
        if (parents.size() == 1 && Pattern.compile("(?<![가-힣])" + parentPattern + "(?=\\s|제|의|$)").matcher(text).find()) {
            String parent = parents.getFirst();
            for (String suffix : List.of("시행령", "시행규칙")) if (names.contains(parent + suffix))
                text = text.replaceAll("(?<![가-힣])(?:(?:같은\\s*법|동법)\\s*)?" + suffix + "(?=\\s*제\\s*\\d+\\s*조)", parent + suffix);
        }
        return compact(text);
    }

    static String explanation(String reason) {
        return switch (reason) {
            case "LEGAL_SCOPE_INCOMPLETE" -> "요약에 법령별 자격 범위가 모두 반영되지 않았습니다. 원문 근거의 전체 자격을 확인해 주세요.";
            case "OUTCOME_NOT_IN_CITATION", "OUTCOME_SCOPE_MERGED" -> "제외 사유와 제재 조치의 연결을 확인해야 합니다. 원문 근거에서 적용 대상과 가능 여부를 확인해 주세요.";
            default -> "일부 자격의 원문 확인이 필요합니다.";
        };
    }

    private static Set<String> effects(String value) {
        return EFFECT.matcher(value).results().map(m -> compact(m.group()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+|[「」｢｣『』\"“”]", "").replace("ㆍ", "·");
    }
}
