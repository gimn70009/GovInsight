package com.publicmonitor.backend.domain.document.service;

import java.util.*;
import java.util.regex.Pattern;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;

/** Explicit obligation rows and complete role columns, with multirow header paths. */
final class SubmissionMatrix {
    private static final Pattern PREFIX = Pattern.compile("^\\[([^\\]\\n]{1,60})\\]\\s*(\\S.*)$");
    private static final Pattern YES = Pattern.compile("[○◯Oo●◎✓√]");
    private static final Pattern NO = Pattern.compile("[Xx×✕✗-]");
    record Obligation(String title, String level, String condition) {}

    static String compact(String text) { return text.replaceAll("\\s+|⏎", ""); }

    static Obligation obligation(String text) {
        var match = PREFIX.matcher(text.strip());
        if (!match.matches()) return null;
        String marker = compact(match.group(1)), level;
        if (marker.contains("해당") || marker.matches(".*(?:시|경우)(?:필수)?$")) level = "CONDITIONAL";
        else if (marker.matches("필수|필수제출")) level = "MANDATORY";
        else if (marker.matches("선택|선택제출")) level = "OPTIONAL";
        else return null;
        String title = match.group(2);
        if (title.length() < 2 || title.length() > 150) return null;
        return new Obligation(title, level, "CONDITIONAL".equals(level) ? match.group(1) : "");
    }

    // null: not this shape; empty: recognized but unsafe, so do not reinterpret as prose.
    static List<SubmissionDocument> read(String[] rawRows, String context, String after, String fileName) {
        if (rawRows.length < 3) return null;
        var rows = Arrays.stream(rawRows).map(row -> row.split("\\t", -1)).toList();
        for (int first = 1; first < Math.min(8, rows.size()); first++) {
            String[] firstRow = rows.get(first);
            var names = new ArrayList<Integer>();
            var roles = new ArrayList<Integer>();
            for (int c = 0; c < firstRow.length; c++) {
                if (obligation(firstRow[c]) != null) names.add(c);
                if (YES.matcher(firstRow[c].strip()).matches() || NO.matcher(firstRow[c].strip()).matches()) roles.add(c);
            }
            if (names.size() != 1 || roles.isEmpty()) continue;
            int name = names.getFirst();
            var completeRoles = new TreeSet<>(roles);
            for (int h = 0; h < first; h++) {
                var head = rows.get(h);
                if (Arrays.stream(head).distinct().count() <= 1) continue;
                var branches = new HashSet<String>();
                for (int c : roles) if (!head[c].isBlank()) branches.add(head[c]);
                for (int c = 0; c < head.length; c++)
                    if (c != name && branches.contains(head[c])) completeRoles.add(c);
            }
            for (int r = first; r < rows.size(); r++) for (int c = 0; c < firstRow.length; c++)
                if (YES.matcher(rows.get(r)[c].strip()).matches() || NO.matcher(rows.get(r)[c].strip()).matches()) completeRoles.add(c);
            roles = new ArrayList<>(completeRoles);
            String envelope = context + "\n" + String.join("\n", Arrays.copyOf(rawRows, first));
            if (!Pattern.compile("(?:제출|신청|접수|구비)\\s*(?:서류|자료)").matcher(envelope).find()) continue;
            var labels = new ArrayList<String>();
            for (int column : roles) {
                String label = "";
                for (int h = 0; h < first; h++) {
                    String[] header = rows.get(h);
                    if (Arrays.stream(header).distinct().count() <= 1) continue;
                    if (!compact(header[column]).isBlank()) label = compact(header[column]);
                }
                if (label.isBlank()) return List.of();
                labels.add(label);
            }
            if (new HashSet<>(labels).size() != labels.size() || after.matches("(?s)^\\s*[*※].*")
                    || Pattern.compile("(?m)^\\s*[*※].*(?:면제|생략|대체|대신|제출하지)").matcher(context).find()) return List.of();
            String stage = "APPLICATION";
            for (String line : context.split("\\R")) {
                if (line.strip().matches("[*※].*")) continue;
                if (line.matches(".*(?:협약|계약)\\s*(?:시|단계|체결|서류).*")) stage = "AGREEMENT";
                else if (line.matches(".*선정\\s*(?:후|이후).*")) stage = "POST_SELECTION";
                else if (line.matches(".*평가\\s*(?:시|단계|서류).*")) stage = "EVALUATION";
                else if (line.matches(".*(?:신청|접수)\\s*서류.*")) stage = "APPLICATION";
            }
            var result = new ArrayList<SubmissionDocument>();
            for (int r = first; r < rows.size(); r++) {
                var row = rows.get(r);
                var value = obligation(row[name]);
                if (value == null) continue;
                var included = new ArrayList<String>();
                var excluded = new ArrayList<String>();
                boolean complete = true;
                for (int c = 0; c < row.length; c++)
                    if (c != name && !roles.contains(c) && !row[c].isBlank()
                            && !row[c].strip().matches("(?:(?:별첨|붙임|첨부|서식|별지)\\s*)?\\d+[.)]?")) complete = false;
                for (int c = 0; c < roles.size(); c++) {
                    String mark = row[roles.get(c)].strip();
                    if (YES.matcher(mark).matches()) included.add(labels.get(c));
                    else if (NO.matcher(mark).matches()) excluded.add(labels.get(c));
                    else complete = false;
                }
                if (!complete || included.isEmpty()) continue;
                var conditions = new ArrayList<String>();
                if (!value.condition().isBlank()) conditions.add(value.condition());
                if (!excluded.isEmpty()) conditions.add("제출 대상 아님: " + String.join(" · ", excluded));
                String target = String.join(" · ", included);
                conditions.addFirst("제출 대상: " + target);
                if (String.join(" / ", conditions).length() > 200) continue;
                var source = new RequirementSource(fileName == null ? "NOTICE_BODY" : "ATTACHMENT",
                        fileName, "제출서류 표 내 목록", "표의 " + (r + 1) + "행", rawRows[r]);
                result.add(new SubmissionDocument(value.title(), rawRows[r], value.level(), stage,
                        target, source, List.of(value.title()), List.copyOf(conditions), List.of()));
            }
            return List.copyOf(result);
        }
        return null;
    }
}
