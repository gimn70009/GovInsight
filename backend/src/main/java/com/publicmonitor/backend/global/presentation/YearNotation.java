package com.publicmonitor.backend.global.presentation;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands abbreviated years in display prose; callers keep source excerpts separate. */
public final class YearNotation {
    private static final String PREFIX = "(?<![\\p{L}\\p{N}_'‘’])['‘’]";
    private static final String END = "(?=$|[\\s()\\[\\]},;:!?。])";
    private static final Pattern PROTECTED = Pattern.compile(
            "\\[[^\\]\\r\\n]+\\]\\(https?://[^\\s<>]+\\)"
            + "|https?://[^\\s<>]+|www\\.[^\\s<>]+"
            + "|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}"
            + "|`[^`\\r\\n]*`"
            + "|[^\\s]+\\.(?i:pdf|hwp|hwpx|zip|docx?|xlsx?|pptx?)(?=$|[\\s),;])");
    private static final String DATE_END = "(?:\\s*일(?!['‘’])|\\.?" + END + ")";
    private static final String DATE_RANGE_END =
            "(?:\\s*일|\\.)?\\s*(?<dateSeparator>[~∼～–-])\\s*"
            + "(?:(?:['‘’](?<endYear>\\d{2})|(?<fullEndYear>(?:19|20|21)\\d{2}))\\s*[./]\\s*)?"
            + "(?:(?<endMonth>\\d{1,2})\\s*[./]\\s*)?(?<endDay>\\d{1,2})(?!\\d)"
            + DATE_END;
    // Limit standalone shorthand to contemporary notice years; ambiguous forms stay unchanged.
    private static final Pattern NOTATION = Pattern.compile(
            PREFIX + "(?<year>\\d{2})(?:"
            + "\\s*(?<separator>[~∼～–-])\\s*['‘’](?<last>\\d{2})"
            + "(?:\\s*(?<rangeUnit>년)(?!['‘’])|" + END + ")"
            + "|\\s*[./]\\s*(?<month>\\d{1,2})"
            + "(?:\\s*[./]\\s*(?<day>\\d{1,2})(?!\\d)"
            + "(?:" + DATE_RANGE_END + "|" + DATE_END + ")"
            + "|\\s*월(?!['‘’])|" + END + ")"
            + "|\\s*년(?!['‘’]))");
    private static final Pattern FULL_YEAR = Pattern.compile("(?<!\\d)((?:19|20|21)\\d{2})\\s*년");

    private YearNotation() {}

    public static String display(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher protectedText = PROTECTED.matcher(text);
        var result = new StringBuilder();
        int end = 0;
        while (protectedText.find()) {
            result.append(expand(text.substring(end, protectedText.start())));
            result.append(protectedText.group());
            end = protectedText.end();
        }
        return result.append(expand(text.substring(end))).toString();
    }

    private static String expand(String text) {
        return NOTATION.matcher(text).replaceAll(match -> Matcher.quoteReplacement(format(text, match)));
    }

    private static String format(String text, MatchResult match) {
        if (Integer.parseInt(match.group("year")) > 49
                || (match.group("last") != null && Integer.parseInt(match.group("last")) > 49)) {
            return match.group();
        }
        int first = year(text, match.start(), match.group("year"));
        if (match.group("last") != null) {
            int last = year(text, match.start(), match.group("last"));
            if (first > last) return match.group();
            return first + match.group("separator") + last + (match.group("rangeUnit") == null ? "" : "년");
        }
        if (match.group("month") != null) {
            int month = Integer.parseInt(match.group("month"));
            String day = match.group("day");
            try {
                LocalDate.of(first, month, day == null ? 1 : Integer.parseInt(day));
            } catch (DateTimeException exception) {
                return match.group();
            }
            String start = first + "년 " + month + "월" + (day == null ? "" : " " + Integer.parseInt(day) + "일");
            if (match.group("endDay") != null) return dateRange(text, match, first, month, Integer.parseInt(day), start);
            return start;
        }
        return first + "년";
    }

    private static String dateRange(String text, MatchResult match, int firstYear, int firstMonth,
            int firstDay, String start) {
        String abbreviated = match.group("endYear");
        if ((abbreviated != null || match.group("fullEndYear") != null) && match.group("endMonth") == null) {
            return match.group();
        }
        if (abbreviated != null && Integer.parseInt(abbreviated) > 49) return match.group();
        int lastYear = match.group("fullEndYear") != null
                ? Integer.parseInt(match.group("fullEndYear"))
                : abbreviated == null ? firstYear : year(text, match.start(), abbreviated);
        int lastMonth = match.group("endMonth") == null ? firstMonth : Integer.parseInt(match.group("endMonth"));
        int lastDay = Integer.parseInt(match.group("endDay"));
        try {
            if (LocalDate.of(lastYear, lastMonth, lastDay).isBefore(LocalDate.of(firstYear, firstMonth, firstDay))) {
                return match.group();
            }
        } catch (DateTimeException exception) {
            return match.group();
        }
        String end = (lastYear == firstYear ? "" : lastYear + "년 ") + lastMonth + "월 " + lastDay + "일";
        return start + match.group("dateSeparator") + end;
    }

    private static int year(String text, int position, String abbreviated) {
        int suffix = Integer.parseInt(abbreviated);
        int result = 2000 + suffix;
        int closest = 81;
        Matcher years = FULL_YEAR.matcher(text);
        while (years.find()) {
            int distance = Math.abs(years.start() - position);
            if (distance >= closest) continue;
            closest = distance;
            int reference = Integer.parseInt(years.group(1));
            int candidate = reference / 100 * 100 + suffix;
            if (candidate - reference > 50) candidate -= 100;
            else if (reference - candidate > 50) candidate += 100;
            result = candidate;
        }
        return result;
    }
}
