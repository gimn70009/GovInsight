package com.publicmonitor.backend.domain.document.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Logical heading boundaries with offsets into the unchanged extracted source. */
final class ParticipationSourceLines {
    private static final Pattern BREAK = Pattern.compile("\\R|(?<=\\h)(?=ㅇ\\h+)|(?=[□■]\\h*[가-힣][가-힣\\h·/]{1,24}[:：])|(?=[□■]\\h*(?:공통\\h*)?(?:가점|감점)\\h*(?:사항|항목|기준))");
    private final String original;
    private final List<Integer> starts = new ArrayList<>();
    private final List<Integer> ends = new ArrayList<>();
    private final List<String> values = new ArrayList<>();

    ParticipationSourceLines(String original) {
        this.original = original;
        var breaks = BREAK.matcher(original);
        int from = 0;
        while (breaks.find()) {
            if (breaks.start() > from || breaks.end() > breaks.start()) add(from, breaks.start());
            from = breaks.end();
        }
        if (from < original.length()) add(from, original.length());
    }

    private void add(int start, int end) {
        starts.add(start);
        ends.add(end);
        values.add(original.substring(start, end));
    }

    String[] lines() { return values.toArray(String[]::new); }

    String excerpt(int startLine, int endLine) {
        return original.substring(starts.get(startLine), ends.get(endLine - 1));
    }
}
