package com.publicmonitor.backend.domain.report;

/** Prevents raw checklist fragments from reaching either HTML or plain-text mail. */
public final class EmailReviewText {
    private EmailReviewText() {}

    public static boolean complete(String text, int limit, int sentences) {
        if (text == null || text.isBlank() || text.length() > limit) return false;
        String value = text.strip();
        if (value.matches("(?s).*[<>\\r\\n↵①-⑳□■—–|:].*")
                || value.matches("(?s).*\\s-\\s.*")
                || value.matches("(?is).*(?:HWPX?|PDF)\\b.*")
                || value.matches("^[0-9]+[.)]\\s.*")
                || !value.matches("(?s).*(?:니다|세요|해요|돼요|예요|이에요)[.!?]?$")) return false;
        return java.util.regex.Pattern.compile("[.!?](?:\\s|$)").matcher(value).results().count() <= sentences;
    }
}
