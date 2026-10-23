package com.publicmonitor.backend.domain.document.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Pattern;

/** A fixed deadline is a calendar fact, not a stored claim about current closure. */
public final class ApplicationDeadlinePresentation {
    private static final Pattern FIXED_DEADLINE = Pattern.compile(
            "^(?<schedule>(?:(?:신청|접수|제출)\\h*(?:마감(?:일시?|시각)?|기한)?\\h*[:：]?\\h*)?"
            + "(?<year>20\\d{2})\\h*(?:[-./]|년)\\h*(?<month>\\d{1,2})\\h*(?:[-./]|월)\\h*"
            + "(?<day>\\d{1,2})(?!\\d)(?:\\h*일|\\.)?"
            + "(?:\\h*\\([월화수목금토일](?:요일)?\\))?"
            + "(?:[ T]+(?:[01]?\\d|2[0-3])(?::[0-5]\\d(?::[0-5]\\d)?|시(?:\\h*[0-5]?\\d\\h*분)?))?"
            + "(?:\\h*\\((?:한국시간|KST)\\))?(?:\\h*까지)?"
            + "(?:\\h*[,;]?\\h*(?:시스템|온라인|전자|이메일|방문|우편)"
            + "(?:\\h*(?:접수|제출|신청))?\\h*기준으로)?)\\h*"
            + "(?:마감(?:되었습니다|됐습니다|하였습니다|했습니다)|종료(?:되었습니다|됐습니다))[.!]?$"
    );

    private ApplicationDeadlinePresentation() {}

    public static String normalize(String text) {
        if (text == null) return null;
        var match = FIXED_DEADLINE.matcher(text.strip());
        if (!match.matches()) return text;
        try {
            LocalDate.of(Integer.parseInt(match.group("year")), Integer.parseInt(match.group("month")),
                    Integer.parseInt(match.group("day")));
        } catch (DateTimeException exception) {
            return text;
        }
        String schedule = match.group("schedule").stripTrailing();
        if (schedule.endsWith("기준으로")) schedule = schedule.substring(0, schedule.length() - 2);
        return schedule + (schedule.endsWith("까지") ? "입니다." : " 마감입니다.");
    }
}
