package com.publicmonitor.backend.domain.report;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/** Date labels are preserved; publication and event dates must not masquerade as deadlines. */
final class ReportSchedulePresentation {
    record Fact(String label, String value, boolean deadline) {}
    private static final Pattern EVENT = Pattern.compile(
            "(?<![가-힣0-9])(?:(?:국문|영문|국내|국외|해외|[가-힣]+측|(?:제[ \\t]*)?\\d+[ \\t]*차)[ \\t]+)*"
            + "(?:[가-힣]+[ \\t]+)?(?:(?:연구개발계획서|연구계획서|사업계획서|계획서)[ \\t]+)?(?:접수[ \\t]*(?:마감|기한|시작|개시|기간)|신청[ \\t]*(?:마감|기한|시작|기간)"
            + "|제출[ \\t]*(?:마감|기한)|의견[ \\t]*(?:마감|기한)|공고(?:일자|일)|게시일|마감(?:일|일자)?|기한|설명회(?:일)?|행사(?:일)?|평가(?:일)?|선정(?:일)?|발표(?:일)?|협약(?:일)?)"
            + "[ \\t]*[:：][ \\t]*");
    private static final Pattern PUBLICATION = Pattern.compile("^(?:[가-힣]+\\s+)?(?:공고(?:일자|일)|게시일)\\s*[:：]\\s*");
    private static final Pattern DEADLINE = Pattern.compile("(?:마감|기한|접수\\s*기간|신청\\s*기간)");
    private static final Pattern PUBLICATION_WITHOUT_COLON = Pattern.compile("^((?:공고일자|공고일|게시일))\\s+(?=\\d{4})");

    private record Marker(int start, String group) {}
    private static final Pattern HEADING_PREFIX = Pattern.compile(
            "(?:[가-힣A-Za-z]+|(?:제[ \\t]*)?\\d+[ \\t]*차)(?:[ \\t]+(?:[가-힣A-Za-z]+|(?:제[ \\t]*)?\\d+[ \\t]*차))*[ \\t]*");

    private ReportSchedulePresentation() {}

    static List<Fact> facts(String label, String value) {
        if (!label.contains("기한") && !label.equals("공고일") && !label.equals("관련 일정")) {
            return List.of(new Fact(label, value, false));
        }
        if (!label.contains("기한")) return List.of(new Fact(label, value, false));
        String text = value.replaceFirst("^표제\\s*[:：]\\s*", "");
        text = PUBLICATION_WITHOUT_COLON.matcher(text).replaceFirst("$1: ");
        var markers = markers(text);
        if (markers.isEmpty()) {
            if (deadlineContext(text) || text.contains("원문") || ReportFactPresentation.unreadableTable(text)) {
                return List.of(new Fact(label, text, confirmedDeadline(text)));
            }
            return List.of(new Fact("관련 일정", text, false), new Fact(label, "원문 확인 필요", false));
        }
        var result = new ArrayList<Fact>();
        if (markers.getFirst().start() > 0) {
            String prefix = trim(text.substring(0, markers.getFirst().start()));
            if (!prefix.isBlank()) result.add(new Fact(deadlineContext(prefix) ? label : "관련 일정", prefix, confirmedDeadline(prefix)));
        }
        for (int i = 0; i < markers.size(); i++) {
            var marker = markers.get(i);
            String segment = trim(text.substring(marker.start(), i + 1 < markers.size() ? markers.get(i + 1).start() : text.length()));
            String markerLabel = marker.group();
            if (PUBLICATION.matcher(markerLabel).find()) {
                result.add(new Fact("공고일", segment.replaceFirst("^(?:공고(?:일자|일)|게시일)\\s*[:：]\\s*", ""), false));
            } else if (DEADLINE.matcher(markerLabel).find()) {
                result.add(new Fact(label, segment, confirmedDeadline(segment)));
            } else {
                result.add(new Fact("관련 일정", segment, false));
            }
        }
        if (result.stream().noneMatch(fact -> fact.label().contains("기한"))) {
            result.add(new Fact(label, "원문 확인 필요", false));
        }
        return result;
    }

    static List<Fact> groupedFacts(String label, String value) {
        record Group(String label, boolean deadline) {}
        var groups = new LinkedHashMap<Group, List<String>>();
        for (var fact : facts(label, value)) {
            groups.computeIfAbsent(new Group(fact.label(), fact.deadline()), ignored -> new ArrayList<>()).add(fact.value());
        }
        return groups.entrySet().stream()
                .map(entry -> new Fact(entry.getKey().label(), String.join("\n", entry.getValue()), entry.getKey().deadline()))
                .toList();
    }

    private static List<Marker> markers(String text) {
        var result = new ArrayList<Marker>();
        var matcher = EVENT.matcher(text);
        while (matcher.find()) {
            if (!outsideParentheses(text, matcher.start())) continue;
            int start = matcher.start();
            int boundaryStart = 0;
            for (char separator : new char[] {'\n', '/', '|', ';', '；'}) {
                boundaryStart = Math.max(boundaryStart, text.lastIndexOf(separator, start - 1) + 1);
            }
            String prefix = text.substring(boundaryStart, start).strip();
            // Multiword captions belong to their physical row or delimited item.
            // Do not borrow dates, conditions or another labelled field above it.
            if (!prefix.isEmpty() && HEADING_PREFIX.matcher(prefix).matches()) start = boundaryStart;
            result.add(new Marker(start, text.substring(start, matcher.end())));
        }
        return result;
    }

    private static boolean confirmedDeadline(String value) {
        return deadlineContext(value) && value.matches("[\\s\\S]*[0-9][\\s\\S]*")
                && !value.contains("원문 확인") && !value.contains("원문 표에서")
                && !value.contains("명시되지 않았") && !value.contains("미확인")
                && !Pattern.compile("추후|예정|미정|확인 필요|별도 안내").matcher(value).find()
                && !ReportFactPresentation.unreadableTable(value);
    }

    private static boolean deadlineContext(String value) {
        return Pattern.compile("마감|기한|(?:접수|신청|제출)\\s*기간|까지|이내|상시|추후|미정").matcher(value).find();
    }

    private static boolean outsideParentheses(String value, int end) {
        int depth = 0;
        for (int i = 0; i < end; i++) {
            char character = value.charAt(i);
            if (character == '(' || character == '（') depth++;
            else if (character == ')' || character == '）') depth = Math.max(0, depth - 1);
        }
        return depth == 0;
    }

    private static String trim(String value) {
        return value.strip().replaceFirst("[\\s/|;；]+$", "").strip();
    }
}
