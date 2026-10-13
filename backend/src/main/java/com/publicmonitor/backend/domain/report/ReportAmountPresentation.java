package com.publicmonitor.backend.domain.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Removes list decoration from saved amounts without rewriting their scope or conditions. */
final class ReportAmountPresentation {
    private static final String HEADING = "(?:지원\\s*규모(?:\\s*(?:및|[·ㆍ/])\\s*(?:지원\\s*)?기간)?|지원\\s*금액|금액[·ㆍ]규모)";
    private static final String MONEY = "\\d[\\d,.]*(?:\\s*(?:조|억|천만|백만|십만|만|천|백))?\\s*원";
    private static final Pattern MONEY_VALUE = Pattern.compile(MONEY);
    private static final Pattern DURATION_VALUE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(년|개월)");
    private static final Pattern BARE_AMOUNT = Pattern.compile("^" + MONEY + "\\s*(?:이내|이하|미만|이상|초과)?$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^\\d+(?:[-.]\\d+)*[.)]\\s*(?=" + HEADING + ")");
    private static final Pattern EMPTY_HEADING = Pattern.compile("^" + HEADING + "\\s*[:：]?$");
    private static final Pattern LABEL_PREFIX = Pattern.compile("^" + HEADING + "\\s*[:：]\\s*");
    private static final Pattern LEADING_MARKER = Pattern.compile(
            "^(?:[•ㅇ○●▪◦▫◇▶▸※*oO]\\s+|-(?=\\s+[가-힣(（])\\s+)");
    private static final Pattern INLINE_MARKER = Pattern.compile(
            "[ \\t]+(?:[•ㅇ○●▪◦▫◇▶▸※]|[oO]|\\*(?=\\s+[가-힣(（]))\\s+");
    private static final Pattern JOINED_FOOTNOTE = Pattern.compile("[ \\t]+\\*(?=[가-힣])");
    private static final Pattern FLATTENED_BUDGET_ROW = Pattern.compile(
            "^(?:품목지정|자유공모|지정공모|지정과제)\\s+\\d+\\s*개\\s+(?:총\\s*)?" + MONEY
                    + "(?:\\s*이내)?\\s+\\d+(?:\\.\\d+)?\\s*(?:년|개월)\\s*(?:이내|이하)?\\s+"
                    + MONEY + "\\s*(?:이내|이하)?$");

    private static final Pattern SQUARE_HEADING = Pattern.compile(
            "(?:^|[ \\t]+)[□■◻◼]\\s*(?=(?:사업\\s*)?(?:기간|예산)|(?:지원\\s*(?:규모|금액|기간|예산)))", Pattern.MULTILINE);
    private static final Pattern BUDGET_PERIOD_HEADING = Pattern.compile(
            "^사업\\s*기간\\s*[/·]\\s*예산\\s*(\\([^\\r\\n)]*\\))?\\s*[:：]\\s*");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[다요함임음]\\.)\\s+");
    private ReportAmountPresentation() {}

    static List<String> paragraphs(String value) {
        String text = SQUARE_HEADING.matcher(value.replace("\r\n", "\n")).replaceAll("\n");
        text = INLINE_MARKER.matcher(text).replaceAll("\n");
        text = JOINED_FOOTNOTE.matcher(text).replaceAll("\n");
        var paragraphs = new ArrayList<String>();
        var tables = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        for (String raw : text.split("\n")) {
            String paragraph = clean(raw);
            if (paragraph.isBlank() || EMPTY_HEADING.matcher(paragraph).matches()) continue;
            if (ReportFactPresentation.unreadableTable(paragraph) || FLATTENED_BUDGET_ROW.matcher(paragraph).matches()) {
                tables.add(paragraph);
                continue;
            }
            String key = paragraph.replaceAll("\\s+", " ");
            if (seen.add(key)) paragraphs.add(paragraph);
        }
        var result = new ArrayList<String>();
        for (String paragraph : paragraphs) {
            if (BARE_AMOUNT.matcher(paragraph).matches() && coveredBareAmount(paragraph, paragraphs)) continue;
            result.add(paragraph);
        }
        // A flattened table has no reliable column relationships. Suppress only duplicate
        // amounts and durations already shown in explicit prose; otherwise retain source-check guidance.
        var knownAmounts = new LinkedHashSet<String>();
        var knownDurations = new LinkedHashSet<String>();
        for (String paragraph : result) {
            if (!BARE_AMOUNT.matcher(paragraph).matches()) {
                MONEY_VALUE.matcher(paragraph).results().map(match -> normalized(match.group())).forEach(knownAmounts::add);
                DURATION_VALUE.matcher(paragraph).results().map(match -> durationMonths(match.group(1), match.group(2))).forEach(knownDurations::add);
            }
        }
        boolean unknownTable = tables.stream().anyMatch(table -> {
            var amounts = MONEY_VALUE.matcher(table).results().map(match -> normalized(match.group())).toList();
            var durations = DURATION_VALUE.matcher(table).results().map(match -> durationMonths(match.group(1), match.group(2))).toList();
            return amounts.isEmpty() || !knownAmounts.containsAll(amounts) || !knownDurations.containsAll(durations);
        });
        if (unknownTable) result.add(ReportFactPresentation.sourceCheckMessage("금액과 지원 조건"));
        return result.stream().flatMap(paragraph -> (paragraph.length() > 160
                ? SENTENCE_BOUNDARY.splitAsStream(paragraph) : java.util.stream.Stream.of(paragraph)))
                .map(String::strip).filter(paragraph -> !paragraph.isEmpty()).toList();
    }

    private static String clean(String value) {
        String paragraph = value.strip();
        String previous;
        do {
            previous = paragraph;
            paragraph = LEADING_MARKER.matcher(paragraph).replaceFirst("").strip();
        } while (!paragraph.equals(previous));
        paragraph = NUMBERED_HEADING.matcher(paragraph).replaceFirst("");
        paragraph = LABEL_PREFIX.matcher(paragraph).replaceFirst("").strip();
        var budget = BUDGET_PERIOD_HEADING.matcher(paragraph);
        if (budget.find()) {
            String fundingScope = budget.group(1) == null ? "" : budget.group(1) + " ";
            paragraph = fundingScope + paragraph.substring(budget.end());
        }
        return paragraph.strip();
    }

    private static boolean coveredBareAmount(String bare, List<String> paragraphs) {
        Pattern exactAmount = Pattern.compile("(?<![\\d.,])" + Pattern.quote(normalized(bare)));
        return paragraphs.stream().filter(other -> !BARE_AMOUNT.matcher(other).matches())
                .anyMatch(other -> exactAmount.matcher(normalized(other)).find());
    }

    private static String durationMonths(String number, String unit) {
        BigDecimal duration = new BigDecimal(number);
        return (unit.equals("년") ? duration.multiply(BigDecimal.valueOf(12)) : duration).stripTrailingZeros().toPlainString();
    }

    private static String normalized(String value) {
        return value.replaceAll("\\s+", "");
    }
}