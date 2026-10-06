package com.publicmonitor.backend.domain.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Groups legacy saved submission links without changing their stored requirements. */
final class ReportSubmissionDownloads {
    private static final Pattern ITEM = Pattern.compile(
            "^• \\[([^\\[\\]\\r\\n]+)\\]\\((https?://[^\\s()<>\"]+)\\)([^\\r\\n]*)$");

    private ReportSubmissionDownloads() {}

    static List<String> group(List<String> content) {
        var result = new ArrayList<>(content);
        var groups = new LinkedHashMap<String, List<Integer>>();
        boolean submissions = false;
        for (int i = 0; i < content.size(); i++) {
            String line = content.get(i).strip();
            if (line.equals("제출 준비 서류 ↓")) {
                flush(content, result, groups);
                submissions = true;
            } else if (submissions && !line.isBlank() && !line.startsWith("• ")) {
                flush(content, result, groups);
                submissions = false;
            } else if (submissions) {
                var match = ITEM.matcher(line);
                if (match.matches()) {
                    String key = match.group(2).split("#", 2)[0];
                    groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
                }
            }
        }
        flush(content, result, groups);
        return result;
    }

    private static void flush(List<String> content, List<String> result,
                              LinkedHashMap<String, List<Integer>> groups) {
        for (var indices : groups.values()) {
            if (indices.size() < 2) continue;
            var labels = new LinkedHashSet<String>();
            String url = null;
            for (int index : indices) {
                var match = ITEM.matcher(content.get(index).strip());
                if (!match.matches()) continue;
                if (url == null) url = match.group(2);
                labels.add(match.group(1) + match.group(3));
                result.set(index, "");
            }
            result.set(indices.getFirst(), labels.size() == 1 ? content.get(indices.getFirst())
                    : "• [통합 서류 파일](" + url + ")\n관련 서류: " + String.join(" · ", labels));
        }
        groups.clear();
    }
}
