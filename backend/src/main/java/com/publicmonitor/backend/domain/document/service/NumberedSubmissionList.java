package com.publicmonitor.backend.domain.document.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Splits explicit, consecutive inline numbers without treating punctuation inside notes as boundaries. */
final class NumberedSubmissionList {
    private static final Pattern NUMBER = Pattern.compile("(?<!\\S)([1-9]\\d?)[.)]\\h*");
    private static final Pattern FORMATTED_NAME = Pattern.compile(
            "[가-힣A-Za-z][가-힣A-Za-z0-9·ㆍ \\t-]{1,60}\\h*[（(](?i:HWPX?|PDF|DOCX?|XLSX?|PPTX?)[)）]");

    private NumberedSubmissionList() {}

    static List<String> items(String text) {
        if (text == null || text.length() > 8_000) return List.of();
        String value = text.strip();
        var result = new ArrayList<String>();
        int depth = 0, start = 0, expected = 1;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (depth == 0 && (i == 0 || ch == ',' || ch == ';')) {
                int boundary = i == 0 ? i : i + 1;
                while (boundary < value.length() && Character.isWhitespace(value.charAt(boundary))) boundary++;
                var marker = NUMBER.matcher(value).region(boundary, value.length());
                if (marker.lookingAt()) {
                    int number = Integer.parseInt(marker.group(1));
                    // Stored display titles may already have had their initial "1)" removed.
                    if (expected == 1 && i > 0) expected = 2;
                    if (number != expected++) return List.of();
                    if (i > 0) result.add(value.substring(start, i).strip());
                    start = marker.end();
                    i = start - 1;
                    continue;
                }
            }
            if (ch == '(' || ch == '（') depth++;
            else if (ch == ')' || ch == '）') {
                if (--depth < 0) return List.of();
            }
        }
        if (depth != 0 || result.isEmpty()) return List.of();
        result.add(value.substring(start).strip());
        // Unknown labels need an explicit document format; procedure prose is not a document name.
        if (result.stream().anyMatch(part -> !SubmissionDocumentResolver.isDocument(part)
                && !FORMATTED_NAME.matcher(part).matches())) return List.of();
        return List.copyOf(result);
    }
}
