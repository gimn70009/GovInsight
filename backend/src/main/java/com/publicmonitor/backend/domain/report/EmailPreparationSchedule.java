package com.publicmonitor.backend.domain.report;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Uses the current delivery time, never a relative date written by an earlier analysis. */
public record EmailPreparationSchedule(String recommendation, String deadlineBadge) {
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(20\\d{2})\\h*(?:[-./]|년)\\h*(\\d{1,2})\\h*(?:[-./]|월)\\h*(\\d{1,2})(?!\\d)(?:\\h*일|\\.)?");
    private static final Pattern TIME = Pattern.compile(
            "(?i)(?<!\\d)(?:(오전|오후|AM|PM)\\h*)?(\\d{1,2})(?::(\\d{2})(?::(\\d{2}))?|시(?:\\h*(\\d{1,2})분)?)(?!\\d)");
    private static final Pattern OTHER_DATE = Pattern.compile(
            "\\d{1,2}\\h*(?:월\\h*\\d{1,2}\\h*일|[./-]\\h*\\d{1,2})");
    private static final Pattern UNCERTAIN = Pattern.compile(
            "상시|수시|예산|소진|선착순|별도|추후|미정|미확인|확인\\h*필요|예정|취소|조기|연장|변경|매월|발표일|시행일|설명회|또는|이후|이전");
    private static final Pattern RANGE_JOIN = Pattern.compile(
            "\\h*(?:\\([월화수목금토일](?:요일)?\\))?\\h*(?:~|∼|～|부터)\\h*");

    public static EmailPreparationSchedule unknown() {
        return new EmailPreparationSchedule("마감일 확인 필요", null);
    }

    public static EmailPreparationSchedule from(String deadline, LocalDateTime sentAt, Integer preparationDays) {
        if (sentAt == null || deadline == null || UNCERTAIN.matcher(deadline).find()) return unknown();
        var matches = new ArrayList<DateMatch>();
        var matcher = DATE.matcher(deadline);
        try {
            while (matcher.find()) {
                matches.add(new DateMatch(LocalDate.of(Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))),
                        matcher.start(), matcher.end()));
            }
            if (matches.isEmpty() || matches.size() > 10) return unknown();
            String outsideDates = DATE.matcher(deadline).replaceAll("");
            outsideDates = TIME.matcher(outsideDates).replaceAll("");
            // Relative counts belong to the old analysis, not to the date grammar.
            outsideDates = outsideDates.replaceAll("(?:남은|잔여|남아 있는)\\h*\\d+\\h*일|D-\\d+(?:일)?", "")
                    .replaceAll("\\d+\\h*일\\h*(?:남았|남아)", "");
            if (OTHER_DATE.matcher(outsideDates).find() || outsideDates.matches("(?s).*\\d+\\h*[월일].*")) return unknown();
            if (matches.size() == 1 && outsideDates.matches("(?s).*[~∼～].*")) return unknown();
            var closing = new ArrayList<LocalDateTime>();
            for (int i = 0; i < matches.size(); i++) {
                var current = matches.get(i);
                int next = i + 1 < matches.size() ? matches.get(i + 1).start() : deadline.length();
                String suffix = deadline.substring(current.end(), next);
                if (i + 1 < matches.size() && RANGE_JOIN.matcher(TIME.matcher(suffix).replaceAll("")).matches()) {
                    if (current.date().isAfter(matches.get(i + 1).date())) return unknown();
                    continue;
                }
                boolean rangeEnd = i > 0 && RANGE_JOIN.matcher(TIME.matcher(
                        deadline.substring(matches.get(i - 1).end(), current.start())).replaceAll("")).matches();
                if (matches.size() > 1 && !rangeEnd) {
                    String prefix = deadline.substring(i == 0 ? 0 : matches.get(i - 1).end(), current.start());
                    if (!prefix.matches("(?s).*(?:전산등록|서류접수|온라인|우편|신청|제출|접수|등록|마감).*")
                            || deadline.matches("(?s).*(?:[1-9]차|국내|해외|현지|택일|선택).*")) return unknown();
                }
                LocalTime at = time(suffix);
                closing.add(current.date().atTime(at == null ? LocalTime.MAX : at));
            }
            if (closing.isEmpty()) return unknown();
            // Both online registration and originals may be mandatory. Do not hide an earlier missed cutoff.
            var end = closing.stream().min(LocalDateTime::compareTo).orElseThrow();
            LocalTime closingTime = end.toLocalTime();
            long days = ChronoUnit.DAYS.between(sentAt.toLocalDate(), end.toLocalDate());
            if (days < 0 || (days == 0 && closingTime != null && !sentAt.toLocalTime().isBefore(closingTime))) {
                return new EmailPreparationSchedule("접수가 마감되었습니다.", "마감 지남");
            }
            String badge = days == 0 ? "오늘 마감 D-day" : "마감까지 D-" + days + "일";
            if (days == 0) return new EmailPreparationSchedule("오늘 마감 · 제출 가능 여부 확인", badge);
            if (preparationDays == null || preparationDays < 1 || preparationDays > 365) {
                return new EmailPreparationSchedule("준비 기간을 설정해 주세요.", badge);
            }
            long margin = days - preparationDays;
            String recommendation = margin > 0 ? margin + "일 내 준비 착수 권장"
                    : margin == 0 ? "오늘 준비 착수 권장" : "최소 준비 기간보다 " + -margin + "일 부족";
            return new EmailPreparationSchedule(recommendation, badge);
        } catch (DateTimeException | IllegalArgumentException exception) {
            return unknown();
        }
    }

    private static LocalTime time(String suffix) {
        var time = TIME.matcher(suffix);
        if (!time.find()) return null;
        int hour = Integer.parseInt(time.group(2));
        String meridiem = time.group(1);
        if (meridiem != null) {
            if (hour < 1 || hour > 12) throw new IllegalArgumentException("Invalid 12-hour time");
            hour = hour % 12 + (meridiem.equals("오후") || meridiem.equalsIgnoreCase("PM") ? 12 : 0);
        }
        int minute = time.group(3) != null ? Integer.parseInt(time.group(3))
                : time.group(5) != null ? Integer.parseInt(time.group(5)) : 0;
        int second = time.group(4) == null ? 0 : Integer.parseInt(time.group(4));
        var result = LocalTime.of(hour, minute, second);
        if (time.find()) throw new IllegalArgumentException("Multiple closing times");
        return result;
    }

    private record DateMatch(LocalDate date, int start, int end) {}
}
