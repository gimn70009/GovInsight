package com.publicmonitor.backend.domain.report;

import com.publicmonitor.backend.global.presentation.YearNotation;
import java.net.URI;
import java.util.regex.Pattern;

/** Formats validated HTTP(S) links while escaping all source text. */
public final class ReportBodyFormatter {
    private static final Pattern LINK = Pattern.compile("\\[([^\\[\\]\\r\\n]+)\\]\\((https?://[^\\s()<>\"]+)\\)");
    private static final Pattern PLAIN_URL = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_@/])(?:https?://|www\\.)[^\\s<>\"\\p{Cntrl}]+");
    private ReportBodyFormatter() {}

    public static String html(String body) {
        return html(body, false);
    }

    public static String emailHtml(String body) {
        return html(body, true);
    }

    private static String html(String body, boolean linkPlainUrls) {
        body = YearNotation.display(body);
        var matcher = LINK.matcher(body);
        var result = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            result.append(plainText(body.substring(end, matcher.start()), linkPlainUrls));
            if (safeUrl(matcher.group(2))) {
                result.append("<a href=\"").append(escape(matcher.group(2)))
                        .append("\">").append(escape(matcher.group(1))).append("</a>");
            } else {
                result.append(escape(matcher.group()));
            }
            end = matcher.end();
        }
        result.append(plainText(body.substring(end), linkPlainUrls));
        return result.toString();
    }

    private static String plainText(String text, boolean linkUrls) {
        if (!linkUrls) return escape(text);
        var matcher = PLAIN_URL.matcher(text);
        var result = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            result.append(escape(text.substring(end, matcher.start())));
            String label = trimUrlEnd(matcher.group());
            String url = label.regionMatches(true, 0, "www.", 0, 4) ? "https://" + label : label;
            if (safeUrl(url)) {
                result.append("<a href=\"").append(escape(url)).append("\">")
                        .append(escape(label)).append("</a>");
            } else {
                result.append(escape(label));
            }
            result.append(escape(text.substring(matcher.start() + label.length(), matcher.end())));
            end = matcher.end();
        }
        return result.append(escape(text.substring(end))).toString();
    }

    private static String trimUrlEnd(String value) {
        // A closing prose bracket may be followed immediately by a Korean particle.
        int end = value.length();
        int[] depth = new int[3];
        for (int i = 0; i < end; i++) {
            int opening = "([{".indexOf(value.charAt(i));
            int closing = ")]}".indexOf(value.charAt(i));
            if (opening >= 0) depth[opening]++;
            if (closing >= 0 && --depth[closing] < 0) end = i;
        }
        // Query/fragment punctuation can be part of the address, so keep it verbatim.
        String candidate = value.substring(0, end);
        if (!candidate.contains("?") && !candidate.contains("#")) {
            while (end > 0 && ".,;:!'’。".indexOf(value.charAt(end - 1)) >= 0) end--;
        }
        return value.substring(0, end);
    }

    public static String telegramHtml(String body) {
        body = YearNotation.display(body);
        return body.lines().map(line -> {
            if (line.startsWith("▸ ") || line.startsWith("◆ ")) return "<b>" + html(line) + "</b>";
            if (line.equals("첨부파일 ↓") || line.equals("제출 준비 서류 ↓")) return "<b>" + html(line) + "</b>";
            if (line.startsWith("• ") && line.contains(": ")) {
                int colon = line.indexOf(": ") + 1;
                return "<b>" + html(line.substring(0, colon)) + "</b>" + html(line.substring(colon));
            }
            return html(line);
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    public static int displayLength(String body) {
        body = YearNotation.display(body);
        var matcher = LINK.matcher(body);
        int length = body.length();
        while (matcher.find()) {
            if (safeUrl(matcher.group(2))) length -= matcher.group().length() - matcher.group(1).length();
        }
        return length;
    }

    private static String escape(String value) {
        // Telegram accepts only a small subset of named HTML entities.
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    static boolean safeUrl(String value) {
        try {
            var uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
