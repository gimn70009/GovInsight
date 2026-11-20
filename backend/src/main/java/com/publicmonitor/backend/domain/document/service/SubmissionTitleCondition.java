package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Expands only explicit, locally attached '(대상 해당 시)' labels; never infers eligibility. */
final class SubmissionTitleCondition {
    private static final Pattern LABEL = Pattern.compile("^(.+?)[（(]([^()（）]+)[)）]$");
    private static final Pattern TARGET = Pattern.compile("^([가-힣A-Za-z0-9 ·ㆍ]+?)(?:에)?\\h*해당\\h*시$");
    private SubmissionTitleCondition() {}

    static SubmissionDocument explain(SubmissionDocument doc) {
        if (doc.source() == null || !doc.source().excerpt().contains(doc.title())) return doc;
        var sentences = sentences(doc.title());
        if (sentences.isEmpty()) return doc;
        var conditions = new ArrayList<String>();
        for (String condition : doc.conditions()) {
            if (key(condition).equals(key(doc.title()))) conditions.addAll(sentences);
            else conditions.add(condition);
        }
        if (conditions.equals(doc.conditions())) return doc;
        return new SubmissionDocument(doc.title(), doc.detail(), doc.requirementLevel(), doc.stage(), doc.appliesTo(),
                doc.source(), doc.documentNames(), List.copyOf(conditions), doc.formats());
    }

    static List<String> sentences(String title) {
        // Choice relationships and nested parentheses require semantic interpretation.
        if (title.matches(".*(?:또는|택일|택\\h*1|중\\h*하나|\\n).*")) return List.of();
        var parts = new ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (c == '(' || c == '（') { if (++depth > 1) return List.of(); }
            if (c == ')' || c == '）') { if (--depth < 0) return List.of(); }
            if (c == ',' && depth == 0) { parts.add(title.substring(start, i).strip()); start = i + 1; }
        }
        if (depth != 0) return List.of();
        parts.add(title.substring(start).strip());
        var sentences = new ArrayList<String>();
        for (String part : parts) {
            var label = LABEL.matcher(part);
            if (!label.matches()) {
                if (part.matches(".*(?:해당|경우|한함|제외|면제).*")) return List.of();
                continue;
            }
            String name = label.group(1).strip(), condition = label.group(2).strip();
            var target = TARGET.matcher(condition);
            if (!target.matches()) {
                if (condition.matches(".*(?:해당|경우|한함|제외|면제).*")) return List.of();
                continue;
            }
            if (name.length() < 2 || name.matches(".*(?:및|제출|해당|경우).*")) return List.of();
            String who = target.group(1).strip();
            if (who.matches(".*(?:제외|면제|제출|미제출).*")) return List.of();
            char last = name.charAt(name.length() - 1);
            String topic = last >= '가' && last <= '힣' && (last - '가') % 28 != 0 ? "은" : "는";
            sentences.add(name + topic + " " + who + "에 해당하는 경우 제출해야 합니다.");
        }
        return List.copyOf(sentences);
    }

    private static String key(String text) { return text.strip().replaceFirst("[.。]$", "").replaceAll("\\s+", ""); }
}
