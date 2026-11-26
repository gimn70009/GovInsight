package com.publicmonitor.backend.domain.document.service;

import java.util.*;
import java.util.regex.Pattern;

/** Split legacy joined scopes only when each complete name is independently established. */
final class ParticipationNamedPrograms {
    private static final Pattern JOIN = Pattern.compile("^\\h*(?:[‧·ㆍ,/]|및|와|과)\\h*");
    private ParticipationNamedPrograms() {}

    static String expand(String value, List<String> names) {
        if (value == null) return null;
        var result = new LinkedHashSet<String>();
        for (String path : value.split(" / ")) {
            // The spaced middle dot already encodes a hierarchy, not siblings.
            if (path.contains(" · ")) { result.add(path); continue; }
            var candidates = names.stream().filter(name -> !name.equals(path) && !name.equals("공통")
                    && !name.contains(" · ") && !name.contains("확인 필요"))
                    .distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
            var matches = new LinkedHashSet<String>();
            split(path, candidates, new ArrayList<>(), matches);
            if (matches.size() == 1) result.addAll(Arrays.asList(matches.iterator().next().split(" / ")));
            else result.add(path);
        }
        return String.join(" / ", result);
    }

    private static void split(String rest, List<String> names, List<String> parts, Set<String> matches) {
        if (parts.size() >= 12 || matches.size() > 1) return;
        for (String name : names) {
            if (!rest.startsWith(name) || parts.contains(name)) continue;
            var next = new ArrayList<>(parts); next.add(name);
            String suffix = rest.substring(name.length());
            if (suffix.isEmpty()) {
                if (next.size() > 1) matches.add(String.join(" / ", next));
            } else {
                var join = JOIN.matcher(suffix);
                if (join.find() && join.end() < suffix.length()) split(suffix.substring(join.end()), names, next, matches);
            }
        }
    }
}
