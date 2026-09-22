package com.publicmonitor.backend.domain.report;

import java.net.URI;
import java.util.regex.Pattern;

/** Only generated HTTP(S) links are formatted; all other source text stays escaped. */
public final class ReportBodyFormatter {
    private static final Pattern LINK = Pattern.compile("\\[([^\\[\\]\\r\\n]+)\\]\\((https?://[^\\s()<>\"]+)\\)");
    private ReportBodyFormatter() {}

    public static String html(String body) {
        var matcher = LINK.matcher(body);
        var result = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            result.append(escape(body.substring(end, matcher.start())));
            if (safeUrl(matcher.group(2))) {
                result.append("<a href=\"").append(escape(matcher.group(2)))
                        .append("\">").append(escape(matcher.group(1))).append("</a>");
            } else {
                result.append(escape(matcher.group()));
            }
            end = matcher.end();
        }
        result.append(escape(body.substring(end)));
        return result.toString();
    }

    public static String telegramHtml(String body) {
        return body.lines().map(line -> {
            if (line.startsWith("▸ ")) return "<b>" + html(line) + "</b>";
            if (line.equals("첨부파일 ↓")) return "<b>" + html(line) + "</b>";
            if (line.startsWith("• ") && line.contains(": ")) {
                int colon = line.indexOf(": ") + 1;
                return "<b>" + html(line.substring(0, colon)) + "</b>" + html(line.substring(colon));
            }
            return html(line);
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    public static int displayLength(String body) {
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

    private static boolean safeUrl(String value) {
        try {
            var uri = URI.create(value);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
