package com.publicmonitor.backend.domain.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Groups explicit contact channels without assigning them to a different institution. */
final class ReportContactPresentation {
    private static final String UNKNOWN = "원문에서 확인해 주세요.";
    private static final String PHONE_NUMBER = "(?:(?:\\(0\\d{1,3}\\)|0\\d{1,3})[-)\\s]+\\d{3,4}[-\\s]+\\d{4}(?:/\\d{4})?|1[568]\\d{2}[-\\s]\\d{4})";
    private static final String EXTENSION = "(?:[\\s,]*(?:\\(?\\s*내선\\s*[:：]?\\s*\\d+(?:[-,]\\d+)*\\s*\\)?|(?:ext\\.?|x)\\s*\\d+))?";
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\dA-Za-z])(?:\\(\\s*" + PHONE_NUMBER + EXTENSION + "\\s*\\)|" + PHONE_NUMBER + ")" + EXTENSION + "(?!\\d)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNWRAPPED_PHONE = Pattern.compile(PHONE_NUMBER + EXTENSION, Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern NON_CONTACT_HEADING = Pattern.compile(
            "^(?:사업명|과제명|공고명|사업\\s*개요|사업\\s*기간|사업\\s*예산|지원\\s*규모|공고번호)\\s*[:：]");
    private static final Pattern REDUNDANT_CONTACT_PREFIX = Pattern.compile("^(.+?)\\s*문의처\\s*[:：]\\s*(.+)$");
    private static final Pattern COURTESY = Pattern.compile(
            "^(?:으로|로|에)?\\s*문의(?:하시기\\s*바랍니다|하여\\s*주시기\\s*바랍니다|해\\s*주시기\\s*바랍니다|해\\s*주세요|바랍니다|하세요)[.!。]?$"
    );
    private static final Pattern EXCLUDED = Pattern.compile("공고기관|중복성\\s*제기|이의\\s*(?:신청|제기)|선정\\s*후|협약\\s*후");
    private static final Pattern LABEL = Pattern.compile("팀|부서|센터|사무국|지원실|지원과|담당|문의처|게시판|접수|연락처|사업단|지원단|운영단|연구원|진흥원|사업부");
    private static final Pattern DETAIL = Pattern.compile("평일|주말|공휴일|점심|운영\\s*시간|상담\\s*시간|문의\\s*가능|통화|제출|불가|제외|필수|반드시|가능합니다|바랍니다");
    private static final Pattern CHANNEL_LABEL = Pattern.compile(
            "(?i)(?:전화(?:번호)?|연락처|이메일|전자우편|e-?mail|tel\\.?|phone|☎)\\s*[:：]?\\s*");

    private static final Pattern TRAILING_CHANNEL_LABEL = Pattern.compile(
            "(?i)(?:^|[\\s(/,])(?:전화(?:번호)?|연락처|이메일|전자우편|e-?mail|tel\\.?|phone|☎)\\s*[:：]?\\s*$");

    record Contact(String label, List<String> phones, List<String> emails, List<String> details) {}
    private record Channel(int start, int end, String value, boolean phone) {}

    private static final class ContactBuilder {
        final String label;
        final LinkedHashSet<String> phones = new LinkedHashSet<>();
        final LinkedHashSet<String> emails = new LinkedHashSet<>();
        final LinkedHashSet<String> details = new LinkedHashSet<>();
        ContactBuilder(String label) { this.label = label; }
        Contact build() { return new Contact(label, List.copyOf(phones), List.copyOf(emails), List.copyOf(details)); }
    }

    private ReportContactPresentation() {}

    static List<Contact> groups(String value) {
        var groups = new LinkedHashMap<String, ContactBuilder>();
        ContactBuilder current = null;
        boolean excludedContinuation = false;
        for (String line : sourceLines(value)) {
            if (EXCLUDED.matcher(line).find()) {
                current = null;
                excludedContinuation = true;
                continue;
            }
            var channels = channels(line);
            if (channels.isEmpty()) {
                if (NON_CONTACT_HEADING.matcher(line).find()) continue;
                String text = cleanLabel(line);
                if (COURTESY.matcher(text).matches()) continue;
                if (text.isBlank() || text.matches(".*원문.*확인.*") || text.equals(UNKNOWN)) continue;
                if (current != null && (DETAIL.matcher(text).find() || !LABEL.matcher(text).find())) {
                    current.details.add(text);
                } else if (!excludedContinuation || LABEL.matcher(text).find()) {
                    current = groups.computeIfAbsent(text, ContactBuilder::new);
                    excludedContinuation = false;
                }
                continue;
            }
            String label = cleanLabel(line.substring(0, channels.getFirst().start()));
            if (!label.isBlank()) {
                current = groups.computeIfAbsent(label, ContactBuilder::new);
                excludedContinuation = false;
            } else if (excludedContinuation) {
                continue;
            } else if (current == null) {
                current = groups.computeIfAbsent("문의", ContactBuilder::new);
            }
            int end = channels.getFirst().start();
            for (Channel channel : channels) {
                String detail = cleanDetail(line.substring(end, channel.start()));
                if (!detail.isBlank()) current.details.add(detail);
                (channel.phone() ? current.phones : current.emails).add(channel.value());
                end = channel.end();
            }
            String detail = cleanDetail(line.substring(end));
            if (!detail.isBlank()) current.details.add(detail);
        }
        if (groups.isEmpty()) return List.of(new Contact("문의", List.of(), List.of(), List.of(UNKNOWN)));
        return groups.values().stream().map(ContactBuilder::build).toList();
    }

    static List<Contact> merge(List<Contact> contacts) {
        var merged = new LinkedHashMap<String, ContactBuilder>();
        for (Contact contact : contacts) {
            String label = cleanLabel(contact.label());
            var group = merged.computeIfAbsent(label, ContactBuilder::new);
            group.phones.addAll(contact.phones());
            group.emails.addAll(contact.emails());
            group.details.addAll(contact.details());
        }
        return merged.values().stream().map(ContactBuilder::build).toList();
    }

    static List<String> lines(String value) {
        var result = new ArrayList<String>();
        for (Contact group : groups(value)) {
            var channels = new ArrayList<String>();
            channels.addAll(group.phones());
            channels.addAll(group.emails());
            String text = channels.isEmpty() ? group.label() : group.label() + ": " + String.join(", ", channels);
            if (!group.details().isEmpty()) {
                String details = String.join(" ", group.details());
                text = group.label().equals("문의") && channels.isEmpty() ? details : text + " — " + details;
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static List<Channel> channels(String line) {
        var result = new ArrayList<Channel>();
        var email = EMAIL.matcher(line);
        while (email.find()) result.add(new Channel(email.start(), email.end(), email.group(), false));
        var phone = PHONE.matcher(line);
        while (phone.find()) {
            int start = phone.start();
            int end = phone.end();
            if (result.stream().noneMatch(channel -> start < channel.end() && end > channel.start())) {
                result.add(new Channel(start, end, displayPhone(phone.group().strip()), true));
            }
        }
        result.sort(Comparator.comparingInt(Channel::start));
        return result;
    }

    private static String displayPhone(String value) {
        if (!value.startsWith("(")) return value;
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '(') depth++;
            else if (value.charAt(i) == ')') depth--;
            if (depth == 0) {
                String number = value.substring(1, i).strip();
                // A full-number wrapper is decorative; area codes and extension parentheses are meaningful.
                return UNWRAPPED_PHONE.matcher(number).matches() ? number + value.substring(i + 1) : value;
            }
        }
        return value;
    }

    private static List<String> sourceLines(String value) {
        if (value == null || value.isBlank()) return List.of();
        String normalized = value.replaceAll("(?:^|\\s+)[□■○●▪•◦▫◇◆▶▷▸►▹]\\s*", "\n")
                .replaceAll("\\s+(?:/|;)\\s+(?=[^/@\\n]{1,90}[:：])", "\n");
        var result = new ArrayList<String>();
        for (String raw : normalized.split("\\R")) {
            String line = raw.replaceFirst("^(?:[\\s•·ㆍㅇ○●□■▪◦▫◇◆▶▷▸►▹↳※*–—-]+|[oO]\\s+)+", "")
                    .replaceAll("\\s+", " ").strip();
            if (!line.isBlank()) result.add(line);
        }
        return result;
    }

    private static String cleanLabel(String text) {
        String label = text.replaceAll("연구개발계획서\\s*전산\\s*등록\\s*관련\\s*문의\\s*[:：]?\\s*", "접수 시스템: ")
                .replaceAll("재무제표\\s*온라인\\s*(?:전용시스템\\s*)?제출\\s*관련\\s*문의\\s*[:：]?\\s*", "재무제표: ")
                .replaceAll("(영문|국문)\\s*연구개발계획서\\s*접수\\s*[:：]?\\s*", "$1 접수: ")
                .replaceFirst("^(?:문의처|문의 담당|담당부서)\\s*[:：]?\\s*", "");
        label = TRAILING_CHANNEL_LABEL.matcher(label).replaceFirst("");
        var repeated = REDUNDANT_CONTACT_PREFIX.matcher(label);
        if (repeated.matches() && repeated.group(2).startsWith(repeated.group(1).strip() + " ")) {
            label = repeated.group(2);
        }
        return label.replaceAll("[\\s:：,;/(]+$", "").replaceAll("^[\\s:：,;/)]+", "")
                .replaceAll("\\s+", " ").strip();
    }

    private static String cleanDetail(String text) {
        String detail = text;
        String channelLabelsOnly = CHANNEL_LABEL.matcher(text).replaceAll("");
        if (channelLabelsOnly.matches("[\\s:：,;/()·]*")) return "";
        detail = detail.replaceAll("^[\\s:：,;/)·]+|[\\s:：,;/(·]+$", "")
                .replaceAll("\\s+", " ").strip();
        return COURTESY.matcher(detail).matches() ? "" : detail;
    }
}
