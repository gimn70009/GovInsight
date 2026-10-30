package com.publicmonitor.backend.domain.document.service;

import java.util.regex.Pattern;

/** Display-only checks shared by model output, stored results, and free source-based sentences. */
final class ParticipationDisplayQuality {
    private static final Pattern BROKEN = Pattern.compile("[\\uFFFD\\p{Co}]");
    private static final Pattern FRAGMENT_START = Pattern.compile("^(?:및|또는|혹은|와|과|은|는|을|를|의)(?:\\s|[:：])");
    private static final Pattern REPEATED_UNIT = Pattern.compile("\\d[\\d,.]*\\s*(년|개월|억원|만원|천원|원|달러|명|개사|개|건|%|점|종)\\s+\\1(?=\\s|$|[.,·])");
    private static final Pattern SECTION = Pattern.compile("[□■]|^[◦○●](?=\\s)|(?:신청\\s*자격|지원\\s*대상|신청\\s*방법)\\s*[:：]");
    private static final Pattern DANGLING_END = Pattern.compile("(?<![가-힣])(?:의|은|는|을|를|및|또는|중|단,?|하는|해당하는|중인|있는|없는|보유한)\\s*(?:이어야|여야|입니다|합니다)");
    private static final Pattern QUALIFIER = Pattern.compile("단[,，]|다만|제외|예외|필수|불가|제한|하여야|해야|신청\\s*대상에\\s*포함|지원\\s*가능|최근|기준|매출|업력|설립|창업|추천|선발");

    static boolean readable(String value) {
        if (value == null || value.isBlank()) return false;
        String text = value.strip();
        return !BROKEN.matcher(text).find() && !FRAGMENT_START.matcher(text).find()
                && !REPEATED_UNIT.matcher(text).find() && !SECTION.matcher(text).find() && !DANGLING_END.matcher(text).find();
    }

    static boolean sentence(String value) {
        return readable(value) && value.matches("(?s).*(?:다|요)(?:\\([^()]*참조\\))?\\.$");
    }

    static boolean qualifier(String note) { return QUALIFIER.matcher(note).find(); }

    /** Do not certify a cached/model clause that stops before its wrapped restriction or adjacent exception. */
    static boolean completeQuote(String source, String quote) {
        int start = source.indexOf(quote);
        if (start < 0) return false;
        String tail = source.substring(start + quote.length());
        String first = tail.split("\\R", 2)[0].strip();
        if (first.matches("^(?:중|이며|이고|이면서|으로서|및|또는|단[,，]|다만)(?:\\s|[,，]).*")) return false;
        String[] lines = tail.split("\\R", -1);
        int next = 0;
        while (next < lines.length && lines[next].isBlank()) next++;
        if (next >= lines.length) return true;
        String line = lines[next].strip();
        if (!line.matches("^(?:[※*]|단[,，]|다만).*")) return true;
        var note = new StringBuilder();
        boolean inNote = false;
        for (int i = next; i < lines.length && note.length() < 1200; i++) {
            String continuation = lines[i].strip();
            if (continuation.isBlank()) { inNote = false; continue; }
            if (continuation.matches("^(?:[□■◦○]|\\[/?표\\]|\\d+[.)]).*")) break;
            if (continuation.matches("^(?:[※*]|단[,，]|다만).*")) inNote = true;
            else if (!inNote) break;
            note.append(' ').append(continuation);
        }
        return !qualifier(note.toString());
    }
}
