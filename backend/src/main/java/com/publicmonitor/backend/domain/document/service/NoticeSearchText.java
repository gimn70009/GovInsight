package com.publicmonitor.backend.domain.document.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Search input only; unknown display values never become evidence. */
final class NoticeSearchText {
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern UNKNOWN = Pattern.compile(
            "(?:(?:원문|본문|자료|첨부파일)(?:에서|에)?\\s*)?"
            + "(?:(?:사업\\s*목적|지원\\s*대상|신청\\s*자격|협력\\s*구조|필수\\s*파트너)(?:을|를|은|는|이|가)?\\s*)?"
            + "(?:확인하지\\s*못했습니다|확인되지\\s*않(?:았습니다|습니다|음)|"
            + "명시(?:되어)?\\s*있지\\s*않(?:았습니다|습니다|음)|미확인|확인\\s*필요|정보\\s*없음|미기재|n/?a|unknown)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCES = Pattern.compile("(?<=[.!?。])(?:\\s+|$)|\\R+");

    private NoticeSearchText() {}

    static String clean(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return Arrays.stream(SENTENCES.split(normalized))
                .map(text -> SPACE.matcher(text).replaceAll(" ").strip().replaceAll("^[.!?。]+\\s*", ""))
                .filter(text -> !text.isBlank() && !isUnknown(text))
                .collect(Collectors.joining(" "));
    }

    static boolean hasUnknown(String value) {
        if (value == null) return false;
        return Arrays.stream(SENTENCES.split(Normalizer.normalize(value, Normalizer.Form.NFKC)))
                .map(text -> text.replaceFirst("^[^:：\\r\\n]{1,20}[:：]\\s*", ""))
                .anyMatch(NoticeSearchText::isUnknown);
    }

    private static boolean isUnknown(String text) {
        return UNKNOWN.matcher(SPACE.matcher(text).replaceAll(" ").strip()
                .replaceAll("[.!?。]+$", "").strip()).matches();
    }

    static String summaryPurpose(String summary) {
        String cleaned = clean(summary);
        // The opening description gives the purpose without copying deadlines or long application guidance.
        return Arrays.stream(SENTENCES.split(cleaned)).limit(2)
                .collect(Collectors.joining(" ")).strip();
    }
}
