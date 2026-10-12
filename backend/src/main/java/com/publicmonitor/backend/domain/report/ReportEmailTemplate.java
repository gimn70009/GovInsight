package com.publicmonitor.backend.domain.report;

import com.publicmonitor.backend.global.presentation.YearNotation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Email-specific presentation of the saved report; no external calls or content generation. */
public final class ReportEmailTemplate {
    private static final Pattern OPPORTUNITY_SCORE = Pattern.compile("기회점수:\\s*([0-9]{1,3})점");

    private enum ScorePalette {
        HIGH("#fef2f2", "#dc2626", "#b91c1c"),
        NORMAL("#e7effb", "#357bd8", "#215fa8"),
        NEUTRAL("#f1f5f9", "#94a3b8", "#475569");

        final String background;
        final String accent;
        final String badge;

        ScorePalette(String background, String accent, String badge) {
            this.background = background;
            this.accent = accent;
            this.badge = badge;
        }
    }

    private ReportEmailTemplate() {}

    public static String render(String title, String body) {
        String normalized = YearNotation.display(body).replace("\r\n", "\n");
        var intro = new ArrayList<String>();
        var cards = new ArrayList<List<String>>();
        List<String> current = intro;
        for (String line : normalized.split("\n", -1)) {
            if (line.startsWith("▸ ")) {
                current = new ArrayList<>();
                cards.add(current);
            }
            if (line.startsWith("  ↳ ") && !current.isEmpty()
                    && current.getLast().startsWith("• ")) {
                current.set(current.size() - 1, current.getLast() + "\n" + line.substring(4));
            } else if (!line.strip().matches("─{3,}")) current.add(line);
        }
        var html = new StringBuilder("<!doctype html><html lang=\"ko\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                + "<body style=\"margin:0;padding:0;background:#f4f6f8;color:#243247;font-family:Arial,'Malgun Gothic',sans-serif\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"background:#f4f6f8\"><tr><td align=\"center\" style=\"padding:24px 10px\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"max-width:720px;table-layout:fixed\">"
                + "<tr><td style=\"padding:28px 24px 24px;background:#193655;border:1px solid #2c4b6b;border-radius:14px\">"
                + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tr>"
                + "<td style=\"color:#a9c9f3;font-size:16px;font-weight:700;letter-spacing:-0.4px\">GovInsight</td>"
                + "<td align=\"right\" style=\"color:#a9c9f3;font-size:10px;font-weight:700;letter-spacing:1.5px\">MONITORING REPORT</td>"
                + "</tr></table>"
                + "<h1 style=\"margin:22px 0 0;color:#ffffff;font-size:24px;font-weight:700;letter-spacing:-0.7px;line-height:1.5;word-break:keep-all;overflow-wrap:anywhere\">");
        html.append(ReportBodyFormatter.html(title)).append("</h1>");
        String overview = String.join("\n", intro.stream().filter(line -> !auxiliaryNote(line.strip())).toList()).strip();
        if (!overview.isBlank()) {
            html.append("<div style=\"margin-top:20px;padding-top:16px;border-top:1px solid #3b5673;color:#c5d5e8;font-size:13px;line-height:1.9;overflow-wrap:anywhere\">")
                    .append(lines(overview)).append("</div>");
        }
        html.append("</td></tr>");
        for (int i = 0; i < cards.size(); i++) html.append(card(cards.get(i), i + 1));
        html.append("<tr><td style=\"padding:24px 16px;color:#65788f;font-size:12px;line-height:1.8;text-align:center\">"
                + "GovInsight · 공공기관 모니터링 보고서"
                + "</td></tr></table></td></tr></table></body></html>");
        return html.toString();
    }

    private static String card(List<String> content, int index) {
        ScorePalette palette = scorePalette(content);
        var html = new StringBuilder("<tr><td style=\"padding-top:20px\"><table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"table-layout:fixed;background:#ffffff;border:1px solid #cddbec;border-radius:14px\">"
                + "<tr><td style=\"padding:24px;background:" + palette.background + ";border-top:4px solid " + palette.accent + ";border-radius:14px 14px 0 0\">"
                + "<p style=\"margin:0 0 10px;font-size:11px;font-weight:700;letter-spacing:1px;color:#4c709d\">공고 " + String.format("%02d", index) + "</p>"
                + "<h2 style=\"margin:0;color:#153957;font-size:22px;line-height:1.5;font-weight:700;overflow-wrap:anywhere;word-break:keep-all\">");
        html.append(ReportBodyFormatter.html(content.getFirst().substring(2))).append("</h2>");
        boolean hasSubmissionFacts = content.stream().anyMatch(line -> line.startsWith("• 제출")
                || line.startsWith("• 공고일:") || line.startsWith("• 관련 일정:"));
        boolean omitSummarySchedule = content.stream().map(String::strip).anyMatch(line ->
                line.startsWith("안내: 요약과 제출 안내의 일정이 다릅니다.")
                || line.startsWith("안내: 요약에 적힌 제출 기한은 원문 근거를 확인하지 못했습니다."));
        boolean headerOpen = true;
        boolean factsOpen = false;
        boolean attachmentsOpen = false;
        boolean submissionCardOpen = false;
        List<String> grouped = ReportSubmissionDownloads.group(content);
        for (int row = 1; row < grouped.size(); row++) {
            String line = grouped.get(row).strip();
            if (line.isBlank()) continue;
            if (auxiliaryNote(line)) {
                if (submissionCardOpen) { html.append("</div>"); submissionCardOpen = false; }
                if (factsOpen) { html.append("</table>"); factsOpen = false; }
                attachmentsOpen = false;
                continue;
            }
            if (headerOpen && line.startsWith("기관: ")) {
                html.append("<p style=\"margin:12px 0 0;color:#476581;font-size:14px;line-height:1.6\">")
                        .append(ReportBodyFormatter.html(line.substring(4))).append("</p>");
                continue;
            }
            if (headerOpen && line.startsWith("문서 유형: ")) {
                html.append("<div style=\"margin-top:12px;line-height:2\">");
                for (String tag : line.substring(7).split("│")) {
                    boolean score = tag.strip().startsWith("기회점수:");
                    html.append("<span style=\"display:inline-block;margin:0 5px 4px 0;padding:3px 9px;border-radius:6px;font-size:12px;")
                            .append(score ? "background:" + palette.badge + ";color:#ffffff;font-weight:700" : "background:#ffffff;color:#4d6480")
                            .append("\">").append(ReportBodyFormatter.html(tag.strip())).append("</span>");
                }
                html.append("</div>");
                continue;
            }
            if (headerOpen) {
                html.append("</td></tr><tr><td style=\"padding:24px\">");
                headerOpen = false;
            }
            if (submissionCardOpen && (line.startsWith("◆ ") || line.startsWith("원문: ")
                    || line.startsWith("추가 제출서류"))) {
                html.append("</div>");
                submissionCardOpen = false;
            }
            if (line.startsWith("◆ ")) {
                html.append("<div data-submission-card style=\"margin:0 0 12px;padding:16px;background:#f3f7fd;border:1px solid #cfddf0;border-radius:10px\">")
                        .append("<h4 style=\"margin:0 0 10px;font-size:16px;color:#193e68;line-height:1.6;overflow-wrap:anywhere\">")
                        .append(ReportBodyFormatter.html(line.substring(2))).append("</h4>");
                submissionCardOpen = true;
                continue;
            }
            if (submissionCardOpen) {
                int colon = line.indexOf(": ");
                html.append("<div style=\"margin:5px 0;font-size:15px;line-height:1.8;overflow-wrap:anywhere\">");
                if (colon >= 0) {
                    html.append("<span style=\"color:#60748e\">")
                            .append(ReportBodyFormatter.html(line.substring(0, colon)))
                            .append("</span> · ").append(linked(line.substring(colon + 2)));
                } else html.append(linked(line));
                html.append("</div>");
                continue;
            }
            if (line.startsWith("요약: ")) {
                html.append(summaryValues(line.substring(4), omitSummarySchedule));
            } else if (line.startsWith("• ") && !attachmentsOpen && line.contains(": ")) {
                if (!factsOpen) {
                    html.append("<h3 style=\"margin:0 0 12px;font-size:17px;color:#243247\">"
                            + (hasSubmissionFacts ? "제출 안내" : "문의 안내") + "</h3>")
                            .append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"table-layout:fixed;border:1px solid #dde5ef;border-radius:10px\">");
                    factsOpen = true;
                }
                int colon = line.indexOf(": ");
                String label = line.substring(2, colon);
                String value = line.substring(colon + 2);
                for (var fact : ReportSchedulePresentation.groupedFacts(label, value)) {
                    html.append(factRow(fact));
                }
            } else {
                if (factsOpen) { html.append("</table>"); factsOpen = false; }
                if (line.equals("제출 준비 서류 ↓")) {
                    html.append("<h3 style=\"margin:24px 0 12px;font-size:17px;color:#243247\">제출 준비 서류</h3>");
                    var items = new ArrayList<String>();
                    int end = row + 1;
                    while (end < grouped.size()) {
                        String entry = grouped.get(end).strip();
                        if (!entry.isBlank() && !entry.startsWith("• ")) break;
                        if (!entry.isBlank()) items.add(entry.substring(2));
                        end++;
                    }
                    if (!items.isEmpty()) {
                        html.append(submissionSections(items));
                        row = end - 1;
                        attachmentsOpen = false;
                    } else {
                        attachmentsOpen = true;
                    }
                } else if (line.equals("첨부파일 ↓")) {
                    html.append("<h3 style=\"margin:24px 0 12px;font-size:17px;color:#243247\">첨부파일 <span style=\"font-weight:400;font-size:12px;color:#718399\">파일명을 눌러 다운로드</span></h3>");
                    attachmentsOpen = true;
                } else if (line.startsWith("원문: ")) {
                    attachmentsOpen = false;
                    html.append("<div style=\"margin-top:22px;padding-top:16px;border-top:1px solid #dce5ef;font-size:14px;font-weight:700\">")
                            .append(linked(line)).append("</div>");
                } else if (attachmentsOpen && line.startsWith("• ")) {
                    html.append("<div style=\"margin:0;padding:7px 0;border-bottom:1px solid #e8edf3;font-size:15px;line-height:1.7;overflow-wrap:anywhere;word-break:break-word\">")
                            .append(submissionEntry(line.substring(2))).append("</div>");
                } else {
                    html.append("<p style=\"font-size:16px;line-height:1.8;overflow-wrap:anywhere\">").append(linked(line)).append("</p>");
                }
            }
        }
        if (submissionCardOpen) html.append("</div>");
        if (factsOpen) html.append("</table>");
        return html.append("</td></tr></table></td></tr>").toString();
    }

    private static ScorePalette scorePalette(List<String> content) {
        for (String raw : content.subList(1, content.size())) {
            String line = raw.strip();
            if (line.isBlank() || line.startsWith("기관: ")) continue;
            if (!line.startsWith("문서 유형: ")) break;
            for (String tag : line.substring(7).split("│")) {
                var match = OPPORTUNITY_SCORE.matcher(tag.strip());
                if (!match.matches()) continue;
                int score = Integer.parseInt(match.group(1));
                if (score > 100) return ScorePalette.NEUTRAL;
                if (score >= 75) return ScorePalette.HIGH;
                return score >= 40 ? ScorePalette.NORMAL : ScorePalette.NEUTRAL;
            }
        }
        return ScorePalette.NEUTRAL;
    }

    private static boolean auxiliaryNote(String line) {
        return line.startsWith("안내:") || line.startsWith("미확인 항목:");
    }

    private static String summaryValues(String value, boolean omitSchedule) {
        var paragraphs = ReportEmailSummary.lines(value, omitSchedule);
        if (paragraphs.isEmpty()) return "";
        var html = new StringBuilder("<p style=\"margin:0 0 8px;font-size:14px;font-weight:700;color:#475569\">핵심 요약</p>"
                + "<div style=\"margin:0 0 24px\">");
        for (String paragraph : paragraphs) {
            html.append("<p data-report-summary-item style=\"margin:0 0 10px;font-size:16px;line-height:1.8;color:#243247;word-break:keep-all;overflow-wrap:anywhere\">")
                    .append(linked(paragraph)).append("</p>");
        }
        return html.append("</div>").toString();
    }

    private static final Pattern SUBMISSION_LINK = Pattern.compile(
            "^\\[[^\\[\\]\\r\\n]+\\]\\((https?://[^\\s()<>\"]+)\\)");

    private static String submissionSections(List<String> items) {
        var forms = new ArrayList<String>();
        var company = new ArrayList<String>();
        for (String item : items) {
            var match = SUBMISSION_LINK.matcher(item);
            boolean downloadable = match.find() && ReportBodyFormatter.safeUrl(match.group(1));
            (downloadable ? forms : company).add(item);
        }
        return submissionSection("공고에서 제공한 양식", forms, true)
                + submissionSection("회사에서 준비할 서류", company, false);
    }

    private static String submissionSection(String title, List<String> items, boolean forms) {
        if (items.isEmpty()) return "";
        var html = new StringBuilder("<div data-submission-section=\"")
                .append(forms ? "provided" : "company")
                .append("\" style=\"margin:16px 0 22px\">")
                .append("<h4 style=\"margin:0 0 6px;font-size:14px;color:#243f5e\">")
                .append(title).append("</h4>");
        for (String item : items) {
            html.append("<div data-submission-list-item style=\"padding:6px 0;border-bottom:1px solid #e8edf3;")
                    .append("font-size:15px;line-height:1.6;overflow-wrap:anywhere;word-break:break-word\">")
                    .append(submissionEntry(item)).append("</div>");
        }
        return html.append("</div>").toString();
    }

    private static String submissionEntry(String text) {
        String[] parts = text.split("\n", 2);
        String result = linked(parts[0]);
        if (parts.length == 2 && !parts[1].stripLeading().startsWith("관련 서류:")) {
            result += "<div style=\"margin-top:6px;color:#526b87;font-size:12px;line-height:1.7\">"
                    + ReportBodyFormatter.html(parts[1]).replace("\n", "<br>") + "</div>";
        }
        return result;
    }

    private static String factRow(ReportSchedulePresentation.Fact fact) {
        String value = fact.value();
        boolean unknown = value.contains("원문 확인") || value.contains("원문 표에서")
                || value.contains("명시되지 않았") || ReportFactPresentation.unreadableTable(value);
        return "<tr data-report-fact=\"" + ReportBodyFormatter.html(fact.label())
                + "\"><td style=\"padding:16px;background:#ffffff;border-bottom:1px solid #e1e8f1\">"
                + "<div style=\"margin-bottom:8px;font-size:14px;color:#475569;font-weight:700\">"
                + ReportBodyFormatter.html(fact.label()) + "</div>"
                + "<div style=\"font-size:16px;line-height:1.7;overflow-wrap:anywhere;color:#243247;"
                + (fact.deadline() && !unknown ? "font-weight:700;" : "")
                + "\">" + factValues(fact.label(), value) + "</div></td></tr>";
    }

    private static final Pattern ROLE_LABEL = Pattern.compile(
            "^((?:국내\\s*|해외\\s*|국외\\s*)?(?:주관기관\\s*외\\s*필수\\s*참여기관|(?:주관|공동|참여)(?:연구개발)?기관|필수\\s*참여기관))\\s*[:：]\\s*([\\s\\S]*)$");

    private static String factValues(String label, String value) {
        var result = new StringBuilder();
        boolean compact = ReportEmailCompactPresentation.supports(label);
        var items = compact ? ReportEmailCompactPresentation.lines(label, value)
                : ReportFactPresentation.lines(label, value);
        for (String item : items) {
            var role = ROLE_LABEL.matcher(item);
            if (label.equals("제출 주체·대상") && role.matches()) {
                result.append("<div data-report-fact-item data-report-role style=\"margin:0 0 10px;line-height:1.7;word-break:keep-all;overflow-wrap:anywhere\">")
                        .append("<div style=\"font-size:14px;font-weight:700;color:#475569\">")
                        .append(ReportBodyFormatter.html(role.group(1))).append(":</div>")
                        .append("<div style=\"margin-top:2px\">").append(linked(role.group(2)).replace("\n", "<br>"))
                        .append("</div></div>");
            } else {
                result.append("<div data-report-fact-item style=\"")
                        .append(!compact && items.size() > 1 ? "padding:3px 0 3px 14px;text-indent:-14px;" : "padding:2px 0;")
                        .append("line-height:1.7;word-break:keep-all;overflow-wrap:anywhere\">")
                        .append(!compact && items.size() > 1 ? "<span aria-hidden=\"true\" style=\"color:#64748b;margin-right:8px\">•</span>" : "")
                        .append(linked(item).replace("\n", "<br>")).append("</div>");
            }
        }
        return result.toString();
    }

    private static String linked(String text) {
        return ReportBodyFormatter.emailHtml(text).replace("<a href=", "<a style=\"color:#205fa9;text-decoration:underline;white-space:normal;word-wrap:break-word;overflow-wrap:anywhere;word-break:break-all\" href=");
    }

    private static String lines(String text) {
        return linked(text).replace("color:#205fa9", "color:#a9c9f3").replace("\n", "<br>");
    }
}
