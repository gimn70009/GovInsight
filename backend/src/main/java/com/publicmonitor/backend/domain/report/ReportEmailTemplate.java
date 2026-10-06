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
        String overview = String.join("\n", intro).strip();
        if (!overview.isBlank()) {
            html.append("<div style=\"margin-top:20px;padding-top:16px;border-top:1px solid #3b5673;color:#c5d5e8;font-size:13px;line-height:1.9;overflow-wrap:anywhere\">")
                    .append(lines(overview)).append("</div>");
        }
        html.append("</td></tr>");
        for (int i = 0; i < cards.size(); i++) html.append(card(cards.get(i), i + 1));
        html.append("<tr><td style=\"padding:24px 16px;color:#65788f;font-size:12px;line-height:1.8;text-align:center\">"
                + "GovInsight · 공공기관 모니터링 보고서<br>제출 전 원문의 최신 안내와 필수 서류를 확인해 주세요."
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
        boolean hasSubmissionFacts = content.stream().anyMatch(line -> line.startsWith("• 제출"));
        boolean headerOpen = true;
        boolean factsOpen = false;
        boolean attachmentsOpen = false;
        boolean submissionCardOpen = false;
        List<String> grouped = ReportSubmissionDownloads.group(content);
        for (int row = 1; row < grouped.size(); row++) {
            String line = grouped.get(row).strip();
            if (line.isBlank()) continue;
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
                html.append("<div style=\"margin:5px 0;font-size:13px;line-height:1.8;overflow-wrap:anywhere\">");
                if (colon >= 0) {
                    html.append("<span style=\"color:#60748e\">")
                            .append(ReportBodyFormatter.html(line.substring(0, colon)))
                            .append("</span> · ").append(linked(line.substring(colon + 2)));
                } else html.append(linked(line));
                html.append("</div>");
                continue;
            }
            if (line.startsWith("요약: ")) {
                html.append("<p style=\"margin:0 0 8px;font-size:12px;font-weight:700;color:#59708c\">사업 요약</p>")
                        .append(summaryValues(line.substring(4)));
            } else if (line.startsWith("• ") && !attachmentsOpen && line.contains(": ")) {
                if (!factsOpen) {
                    html.append("<h3 style=\"margin:0 0 12px;font-size:15px;color:#243f5e\">"
                            + (hasSubmissionFacts ? "제출 안내" : "문의 안내") + "</h3>")
                            .append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"table-layout:fixed;border:1px solid #dde5ef;border-radius:10px\">");
                    factsOpen = true;
                }
                int colon = line.indexOf(": ");
                String label = line.substring(2, colon);
                String value = line.substring(colon + 2);
                boolean unknown = value.contains("원문 확인") || value.contains("원문 표에서")
                        || value.contains("명시되지 않았") || ReportFactPresentation.unreadableTable(value);
                html.append("<tr><td style=\"padding:13px 14px;background:#f3f6fa;border-bottom:1px solid #e1e8f1\">")
                        .append("<div style=\"margin-bottom:5px;font-size:12px;color:#526b87;font-weight:700\">")
                        .append(ReportBodyFormatter.html(label)).append("</div>")
                        .append("<div style=\"font-size:14px;line-height:1.85;overflow-wrap:anywhere;")
                        .append(unknown ? "color:#78879a" : label.contains("기한") ? "color:#215fa8;font-weight:700" : "color:#253d59")
                        .append("\">").append(factValues(label, value)).append("</div></td></tr>");
            } else {
                if (factsOpen) { html.append("</table>"); factsOpen = false; }
                if (line.equals("제출 준비 서류 ↓")) {
                    html.append("<h3 style=\"margin:24px 0 12px;font-size:15px;color:#243f5e\">제출 준비 서류</h3>");
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
                    html.append("<h3 style=\"margin:24px 0 12px;font-size:15px;color:#243f5e\">첨부파일 <span style=\"font-weight:400;font-size:12px;color:#718399\">파일명을 눌러 다운로드</span></h3>");
                    attachmentsOpen = true;
                } else if ((line.startsWith("미확인 항목: ") || line.startsWith("안내: "))) {
                    attachmentsOpen = false;
                    html.append("<p data-report-note style=\"margin:16px 0 0;font-size:12px;line-height:1.7;color:#687c95;overflow-wrap:anywhere\">")
                            .append(ReportBodyFormatter.html(line)).append("</p>");
                } else if (line.startsWith("원문: ")) {
                    attachmentsOpen = false;
                    html.append("<div style=\"margin-top:22px;padding-top:16px;border-top:1px solid #dce5ef;font-size:14px;font-weight:700\">")
                            .append(linked(line)).append("</div>");
                } else if (attachmentsOpen && line.startsWith("• ")) {
                    html.append("<div style=\"margin:0 0 8px;padding:12px 14px;background:#f0f5fc;border:1px solid #d7e4f5;border-radius:8px;font-size:13px;line-height:1.7;overflow-wrap:anywhere;word-break:break-word\">")
                            .append(submissionEntry(line.substring(2))).append("</div>");
                } else {
                    html.append("<p style=\"font-size:14px;line-height:1.8;overflow-wrap:anywhere\">").append(linked(line)).append("</p>");
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

    private static String summaryValues(String value) {
        var html = new StringBuilder("<div style=\"margin:0 0 24px\">");
        for (String paragraph : ReportFactPresentation.lines("사업 요약", value)) {
            html.append("<p data-report-summary-item style=\"margin:0 0 10px;font-size:15px;line-height:1.85;color:#344c65;word-break:keep-all;overflow-wrap:anywhere\">")
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
        return submissionSection("공고에서 제공한 양식", "파일을 내려받아 작성해 주세요.", forms, true)
                + submissionSection("회사에서 준비할 서류",
                    "회사 자료를 준비하거나 발급받아 주세요. 링크가 없는 양식은 공고 원문에서 확인해 주세요.",
                    company, false);
    }

    private static String submissionSection(String title, String description, List<String> items, boolean forms) {
        if (items.isEmpty()) return "";
        var html = new StringBuilder("<div data-submission-section=\"")
                .append(forms ? "provided" : "company")
                .append("\" style=\"margin:16px 0 22px\">")
                .append("<h4 style=\"margin:0 0 6px;font-size:14px;color:#243f5e\">")
                .append(title).append("</h4>")
                .append("<p style=\"margin:0 0 10px;color:#60748e;font-size:12px;line-height:1.7\">")
                .append(description).append("</p>");
        for (String item : items) {
            html.append("<div style=\"margin:0 0 8px;padding:12px 14px;border:1px solid ")
                    .append(forms ? "#d7e4f5;background:#f0f5fc;" : "#e0e5eb;background:#f7f8fa;")
                    .append("border-radius:8px;font-size:13px;line-height:1.7;overflow-wrap:anywhere;word-break:break-word\">")
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

    private static String factValues(String label, String value) {
        var result = new StringBuilder();
        var items = ReportFactPresentation.lines(label, value);
        for (String item : items) {
            result.append("<div data-report-fact-item style=\"")
                    .append(items.size() > 1 ? "padding:6px 0 6px 14px;text-indent:-14px;" : "padding:4px 0;")
                    .append("line-height:1.65;word-break:keep-all;overflow-wrap:anywhere\">")
                    .append(items.size() > 1 ? "<span aria-hidden=\"true\" style=\"color:#59708c;margin-right:8px\">•</span>" : "")
                    .append(linked(item)).append("</div>");
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
