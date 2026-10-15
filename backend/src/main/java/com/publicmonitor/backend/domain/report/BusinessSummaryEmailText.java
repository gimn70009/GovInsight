package com.publicmonitor.backend.domain.report;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact won-unit presentation matching frontend/src/utils/businessSummary.ts. */
final class BusinessSummaryEmailText {
    private static final Map<String, Integer> POWERS = Map.ofEntries(
            Map.entry("", 0), Map.entry("백", 2), Map.entry("천", 3), Map.entry("만", 4),
            Map.entry("십만", 5), Map.entry("백만", 6), Map.entry("천만", 7), Map.entry("억", 8),
            Map.entry("십억", 9), Map.entry("백억", 10), Map.entry("천억", 11), Map.entry("조", 12));
    private static final Pattern AMOUNT = Pattern.compile(
            "(?<![\\d.,A-Za-z+-])([+-]?\\d[\\d,]*(?:\\.\\d+)?)"
                    + "(?:([ \\t]*(?:~|∼|–|—|－|-|부터)[ \\t]*)([+-]?\\d[\\d,]*(?:\\.\\d+)?))?"
                    + "[ \\t]*(천억|백억|십억|천만|백만|십만|조|억|만|천|백)?[ \\t]*원");
    private static final Pattern COMPOUND = Pattern.compile("\\d[\\d,.]*[ \\t]*(?:조|억|만|천|백)[ \\t]*$");
    private static final Pattern SHARED_LIST = Pattern.compile("\\d[ \\t]*[~∼–—－/·,-][ \\t]*$");
    private BusinessSummaryEmailText() {}

    static String supportScale(String text) {
        var matcher = AMOUNT.matcher(text);
        var output = new StringBuilder();
        while (matcher.find()) {
            String prefix = text.substring(0, matcher.start());
            String replacement = matcher.group();
            if (!COMPOUND.matcher(prefix).find() && !SHARED_LIST.matcher(prefix).find()) {
                String unit = matcher.group(4) == null ? "" : matcher.group(4);
                String lower = readableWon(matcher.group(1), unit);
                String upper = matcher.group(3) == null ? null : readableWon(matcher.group(3), unit);
                if (lower != null && (matcher.group(3) == null || upper != null)) {
                    replacement = upper == null ? lower : lower + matcher.group(2) + upper;
                }
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(output).toString();
    }

    private static String readableWon(String number, String unit) {
        if (!number.matches("[+-]?(?:\\d+|\\d{1,3}(?:,\\d{3})+)(?:\\.\\d+)?")) return null;
        BigInteger remaining;
        try {
            remaining = new BigDecimal(number.replace(",", "")).movePointRight(POWERS.get(unit)).toBigIntegerExact().abs();
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
        var parts = new ArrayList<String>();
        var format = NumberFormat.getIntegerInstance(Locale.KOREA);
        int[] powers = {12, 8, 4};
        String[] labels = {"조", "억", "만"};
        for (int index = 0; index < powers.length; index++) {
            var amounts = remaining.divideAndRemainder(BigInteger.TEN.pow(powers[index]));
            if (amounts[0].signum() > 0) parts.add(format.format(amounts[0]) + labels[index]);
            remaining = amounts[1];
        }
        String sign = number.startsWith("-") ? "-" : number.startsWith("+") ? "+" : "";
        if (remaining.signum() > 0 || parts.isEmpty()) {
            parts.add(format.format(remaining) + "원");
            return sign + String.join(" ", parts);
        }
        return sign + String.join(" ", parts) + " 원";
    }
}
