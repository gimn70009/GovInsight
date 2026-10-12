package com.publicmonitor.backend.domain.report;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** A short email excerpt; the saved report retains the complete analysis summary. */
final class ReportEmailSummary {
    private static final int MAX_SENTENCES = 2;
    private static final int PREFERRED_CHARACTERS = 260;

    private static final Pattern SUBMISSION_SCHEDULE = Pattern.compile(
            "마감|기한|(?:신청|접수|제출)\\s*(?:기간|일정|일자)"
            + "|(?:신청|접수|제출)(?:은|는)?\\s*(?:20\\d{2}|\\d{1,2}[월./-])"
            + "|(?:까지|부터).{0,15}(?:신청|접수|제출)");
    private static final Pattern DATE_OR_TIME = Pattern.compile(
            "(?<!\\d)(?:20\\d{2}\\s*년\\s*\\d{1,2}\\s*월|(?:20\\d{2}[./-])?\\d{1,2}[./-]\\d{1,2}"
            + "(?!\\d|\\s*(?:억|만|천|원|%))"
            + "|\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일|\\d{1,2}\\s*시|\\d{1,2}:\\d{2})");

    private ReportEmailSummary() {}

    static List<String> lines(String value) {
        return lines(value, false);
    }

    static List<String> lines(String value, boolean omitSchedule) {
        var result = new ArrayList<String>();
        int characters = 0;
        String sentences = value.replaceAll("(?<=[다요]\\.)\\s+", "\n");
        for (String sentence : ReportFactPresentation.lines("사업 요약", sentences)) {
            // A hidden diagnostic must not leave the disputed deadline in the email summary.
            if (omitSchedule && SUBMISSION_SCHEDULE.matcher(sentence).find()
                    && DATE_OR_TIME.matcher(sentence).find()) continue;
            // Keep whole sentences, including dates, names, amounts and links.
            if (!result.isEmpty() && (result.size() >= MAX_SENTENCES
                    || characters + sentence.length() > PREFERRED_CHARACTERS)) break;
            result.add(sentence);
            characters += sentence.length();
        }
        return List.copyOf(result);
    }
}
