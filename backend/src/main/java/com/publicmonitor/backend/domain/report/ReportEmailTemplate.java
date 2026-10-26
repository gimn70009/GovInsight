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
        List<String> grouped = ReportSubmissionDownloads.group(content);
        var header = ReportOpportunityPresentation.header(grouped);
        ScorePalette palette = scorePalette(header);
        var html = new StringBuilder("<tr><td style=\"padding-top:20px\"><table data-report-card role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"table-layout:fixed;background:#ffffff;border:1px solid #cddbec;border-radius:14px\">"
                + "<tr><td style=\"padding:22px 20px;background:" + palette.background + ";border-top:4px solid " + palette.accent + ";border-radius:14px 14px 0 0\">"
                + "<p style=\"margin:0 0 10px;font-size:11px;font-weight:700;letter-spacing:1px;color:#4c709d\">공고 " + String.format("%02d", index) + "</p>"
                + "<h2 style=\"margin:0;color:#153957;font-size:22px;line-height:1.5;font-weight:700;overflow-wrap:anywhere;word-break:keep-all\">");
        html.append(ReportBodyFormatter.html(content.getFirst().substring(2))).append("</h2>");
        if (!header.institution().isBlank()) {
            html.append("<p style=\"margin:12px 0 0;color:#476581;font-size:14px;line-height:1.6;overflow-wrap:anywhere\">")
                    .append(ReportBodyFormatter.html(header.institution())).append("</p>");
        }
        appendBadges(html, header, palette);
        html.append("</td></tr><tr><td style=\"padding:20px 16px 8px\">");
        boolean omitSummarySchedule = content.stream().map(String::strip).anyMatch(line ->
                line.startsWith("안내: 요약과 제출 안내의 일정이 다릅니다.")
                || line.startsWith("안내: 요약에 적힌 제출 기한은 원문 근거를 확인하지 못했습니다."));
        var amounts = new ArrayList<ReportSchedulePresentation.Fact>();
        var participants = new ArrayList<ReportSchedulePresentation.Fact>();
        var schedule = new ArrayList<ReportSchedulePresentation.Fact>();
        var contacts = new ArrayList<ReportSchedulePresentation.Fact>();
        var others = new ArrayList<ReportSchedulePresentation.Fact>();
        var remainder = new ArrayList<String>();
        var originals = new ArrayList<String>();
        var summaries = new ArrayList<String>();
        boolean documents = false;
        for (int row = header.bodyStart(); row < grouped.size(); row++) {
            String line = grouped.get(row).strip();
            if (line.isBlank() || auxiliaryNote(line)) continue;
            if (line.startsWith("요약: ") && !documents) {
                summaries.add(line.substring(4));
                continue;
            }
            if (line.startsWith("원문: ")) {
                originals.add(line);
                documents = false;
                continue;
            }
            if (line.equals("제출 준비 서류 ↓") || line.equals("첨부파일 ↓")) documents = true;
            if (!documents && line.startsWith("• ") && line.contains(": ")) {
                int colon = line.indexOf(": ");
                String label = line.substring(2, colon);
                String value = line.substring(colon + 2);
                for (var fact : ReportSchedulePresentation.groupedFacts(label, value)) {
                    if (header.generalNotice() && ReportOpportunityPresentation.unknownOnly(fact.value())) continue;
                    if (fact.label().equals("금액·규모")) {
                        if (!ReportAmountPresentation.paragraphs(fact.value()).isEmpty()) amounts.add(fact);
                    } else if (fact.label().equals("참여 역할") || fact.label().equals("제출 주체·대상")
                            || fact.label().equals("참여조건")) participants.add(fact);
                    else if (fact.label().startsWith("문의")) contacts.add(fact);
                    else if (fact.label().startsWith("제출") || fact.label().equals("공고일")
                            || fact.label().equals("관련 일정")) schedule.add(fact);
                    else others.add(fact);
                }
                continue;
            }
            if (!(header.generalNotice() && line.startsWith("• ")
                    && ReportOpportunityPresentation.unknownOnly(line.substring(2)))) remainder.add(line);
        }
        var summarySchedule = schedule.stream().map(fact -> {
            if (!header.generalNotice() && fact.label().equals("제출처·방법")) {
                return new ReportSchedulePresentation.Fact(fact.label(),
                        String.join("\n", ReportEmailCompactPresentation.lines(fact.label(), fact.value())), fact.deadline());
            }
            return fact;
        }).toList();
        for (String summary : summaries) html.append(summaryValues(summary, omitSummarySchedule, amounts, summarySchedule));
        html.append(factSection("amount", "금액·규모", amounts, true));
        html.append(factSection("participation", "참여조건", participationFacts(participants, amounts), false));
        html.append(factSection("submission", "제출 안내", schedule, false, !header.generalNotice()));
        html.append(factSection("details", "상세 안내", others, false));
        html.append(documentSections(remainder));
        html.append(contactSection(contacts));
        for (String original : originals) html.append(originalLink(original));
        return html.append("</td></tr></table></td></tr>").toString();
    }

    private static final Pattern ORIGINAL_LINK = Pattern.compile("^\\[[^\\[\\]\\r\\n]+\\]\\((https?://[^\\s()<>\"]+)\\)$");

    private static String originalLink(String original) {
        String value = original.substring("원문: ".length()).strip();
        var match = ORIGINAL_LINK.matcher(value);
        String url = match.matches() ? match.group(1) : value;
        var html = new StringBuilder("<div data-report-original style=\"margin:4px 0 16px;overflow-wrap:anywhere\">");
        if (ReportBodyFormatter.safeUrl(url)) {
            html.append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"table-layout:fixed\"><tr>"
                    + "<td align=\"center\" bgcolor=\"#193e68\" style=\"background:#193e68;border:1px solid #193e68;border-radius:8px\">")
                    .append("<a data-report-original-link href=\"").append(url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;"))
                    .append("\" style=\"display:block;padding:12px 16px;color:#ffffff;font-size:16px;line-height:1.5;font-weight:700;text-decoration:none;word-break:keep-all;overflow-wrap:anywhere\">공고 원문 보기</a>")
                    .append("</td></tr></table>");
        } else {
            html.append("<p style=\"margin:0;padding:12px 0;color:#526780;font-size:14px;line-height:1.7\">")
                    .append(linked(original)).append("</p>");
        }
        return html.append("</div>").toString();
    }
    private static void appendBadges(StringBuilder html, ReportOpportunityPresentation.Header header, ScorePalette palette) {
        var tags = header.badges();
        if (!tags.isEmpty()) {
            html.append("<div data-report-badges style=\"margin-top:16px;line-height:2\">");
            for (String tag : tags) {
                boolean score = tag.startsWith("기회점수:");
                html.append("<span ").append(score ? "data-report-score " : tag.equals(header.category()) ? "data-report-category " : "data-report-status ")
                        .append("style=\"display:inline-block;max-width:100%;box-sizing:border-box;vertical-align:middle;margin:0 8px 6px 0;")
                        .append("padding:6px 10px;border-radius:8px;font-size:16px;")
                        .append("line-height:1.7;overflow-wrap:anywhere;")
                        .append(score ? "background:" + palette.badge + ";color:#ffffff;font-weight:700" : "background:#ffffff;color:#4d6480;font-weight:700")
                        .append("\">").append(ReportBodyFormatter.html(tag)).append("</span>");
            }
            html.append("</div>");
        }
        html.append("<table data-report-classification role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"table-layout:fixed;margin-top:8px;background:#ffffff;border:1px solid #cfdaea;border-radius:8px\">");
        appendClassificationRow(html, "type", "유형:", header.typeDisplay(), false);
        appendClassificationRow(html, "method", "방식:", header.methodDisplay(), true);
        html.append("</table>");
    }

    private static void appendClassificationRow(StringBuilder html, String key, String label, String value, boolean divided) {
        String border = divided ? "border-top:1px solid #e3eaf3;" : "";
        html.append("<tr data-report-meta=\"").append(key).append("\"><td width=\"48\" style=\"width:48px;vertical-align:top;padding:10px 0 10px 12px;")
                .append(border).append("font-size:14px;line-height:1.8;color:#526780;\">")
                .append(label).append("</td><td style=\"padding:10px 12px 10px 4px;vertical-align:top;")
                .append(border).append("font-size:16px;line-height:1.6;font-weight:700;color:#213b58;word-break:keep-all;overflow-wrap:anywhere;\">")
                .append(ReportBodyFormatter.html(value)).append("</td></tr>");
    }

    private static String factSection(String key, String title, List<ReportSchedulePresentation.Fact> facts,
                                      boolean hideSingleLabel) {
        return factSection(key, title, facts, hideSingleLabel, true);
    }

    private static String factSection(String key, String title, List<ReportSchedulePresentation.Fact> facts,
                                      boolean hideSingleLabel, boolean compact) {
        if (facts.isEmpty()) return "";
        var html = new StringBuilder(sectionStart(key, title));
        for (var fact : facts) html.append(factRow(fact, !(hideSingleLabel && facts.size() == 1), compact));
        return html.append("</table></td></tr></table>").toString();
    }

    private static List<ReportSchedulePresentation.Fact> participationFacts(
            List<ReportSchedulePresentation.Fact> facts, List<ReportSchedulePresentation.Fact> amounts) {
        var knownAmounts = amounts.stream().map(fact -> fact.value().replaceAll("\\s+", ""))
                .toList();
        var result = new ArrayList<ReportSchedulePresentation.Fact>();
        for (var fact : facts) {
            if (!fact.label().equals("참여 역할")) { result.add(fact); continue; }
            var lines = ReportFactPresentation.lines(fact.label(), fact.value()).stream().filter(line -> {
                boolean budgetOnly = line.matches("^(?:지원\\s*(?:예산|기간)|신규\\s*예산|정부예산).*" )
                        && !line.matches(".*(?:참여|신청|자격|컨소시엄|공동수급|기업|기관|법인|필수).*" );
                String normalized = line.replaceAll("\\s+", "").replaceFirst("[.。]$", "");
                return !budgetOnly || knownAmounts.stream().noneMatch(amount -> amount.contains(normalized));
            }).toList();
            if (!lines.isEmpty()) result.add(new ReportSchedulePresentation.Fact(
                    fact.label(), String.join("\n", lines), fact.deadline()));
        }
        return result;
    }

    private static String contactSection(List<ReportSchedulePresentation.Fact> facts) {
        var groups = new ArrayList<ReportContactPresentation.Contact>();
        for (var fact : facts) {
            if (ReportFactPresentation.unreadableTable(fact.value())) {
                groups.add(new ReportContactPresentation.Contact("문의", List.of(), List.of(),
                        List.of(ReportFactPresentation.sourceCheckMessage("문의 담당"))));
            } else groups.addAll(ReportContactPresentation.groups(fact.value()));
        }
        if (groups.isEmpty()) return "";
        var html = new StringBuilder(sectionStart("contact", "문의 안내"));
        for (var contact : ReportContactPresentation.merge(groups)) {
            html.append("<tr><td style=\"padding:12px 16px;border-bottom:1px solid #e3eaf3\">")
                    .append("<table data-report-contact-group role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"table-layout:fixed\">")
                    .append("<colgroup><col width=\"56\" style=\"width:56px\"><col></colgroup><tr><td colspan=\"2\" data-report-contact-label style=\"padding:0 0 8px;font-size:16px;font-weight:700;color:#213b58;line-height:1.7;overflow-wrap:anywhere\">")
                    .append(linked(contact.label())).append("</td></tr>");
            appendContactChannel(html, "phone", "전화", contact.phones());
            appendContactChannel(html, "email", "이메일", contact.emails());
            appendContactChannel(html, "detail", "안내", contact.details());
            html.append("</table></td></tr>");
        }
        return html.append("</table></td></tr></table>").toString();
    }

    private static void appendContactChannel(StringBuilder html, String kind, String label, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            html.append("<tr data-report-contact-channel=\"").append(kind).append("\"><td width=\"56\" style=\"width:56px;vertical-align:top;padding:3px 8px 3px 0;font-size:14px;line-height:1.6;white-space:nowrap;color:#60748e\">")
                    .append(i == 0 ? label : "")
                    .append("</td><td data-report-contact-value style=\"padding:3px 0;vertical-align:top;font-size:16px;line-height:1.6;color:#243247;word-break:keep-all;overflow-wrap:anywhere\">")
                    .append(kind.equals("detail") ? linked(values.get(i)) : ReportBodyFormatter.html(values.get(i))).append("</td></tr>");
        }
    }

    private static String sectionStart(String key, String title) {
        return "<table data-report-section=\"" + key + "\" role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" "
                + "style=\"table-layout:fixed;margin:0 0 16px;border:1px solid #dce5ef;border-radius:10px;background:#ffffff\">"
                + "<tr><td style=\"padding:12px 16px;background:#f5f8fc;border-bottom:1px solid #dce5ef;border-radius:10px 10px 0 0\">"
                + "<h3 style=\"margin:0;font-size:15px;line-height:1.6;font-weight:700;color:#243f5e\">" + title + "</h3></td></tr>"
                + "<tr><td><table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"table-layout:fixed\">";
    }

    private static String documentSections(List<String> content) {
        var html = new StringBuilder();
        boolean sectionOpen = false;
        boolean attachmentsOpen = false;
        boolean submissionCardOpen = false;
        for (int row = 0; row < content.size(); row++) {
            String line = content.get(row);
            if (line.equals("제출 준비 서류 ↓") || line.equals("첨부파일 ↓")) {
                if (submissionCardOpen) { html.append("</div>"); submissionCardOpen = false; }
                if (sectionOpen) html.append("</td></tr></table></td></tr></table>");
                boolean submission = line.equals("제출 준비 서류 ↓");
                int next = row + 1;
                if (next >= content.size() || content.get(next).equals("제출 준비 서류 ↓")
                        || content.get(next).equals("첨부파일 ↓")) { sectionOpen = false; continue; }
                html.append(sectionStart(submission ? "documents" : "attachments", submission ? "제출 준비 서류"
                                : "첨부파일 <span style=\"font-weight:400;font-size:12px;color:#718399\">파일명을 눌러 다운로드</span>"))
                        .append("<tr><td style=\"padding:0 16px 12px\">");
                sectionOpen = true;
                attachmentsOpen = true;
                if (submission) {
                    var items = new ArrayList<String>();
                    while (next < content.size() && content.get(next).startsWith("• ")) {
                        items.add(content.get(next).substring(2));
                        next++;
                    }
                    if (!items.isEmpty()) {
                        html.append(submissionSections(items));
                        row = next - 1;
                    }
                }
                continue;
            }
            if (submissionCardOpen && (line.startsWith("◆ ") || line.startsWith("추가 제출서류"))) {
                html.append("</div>");
                submissionCardOpen = false;
            }
            if (line.startsWith("◆ ")) {
                html.append("<div data-submission-card style=\"margin:12px 0;padding:16px;background:#f3f7fd;border:1px solid #cfddf0;border-radius:10px\">")
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
                            .append("</span>: ").append(linked(line.substring(colon + 2)));
                } else html.append(linked(line));
                html.append("</div>");
                continue;
            }
            if (attachmentsOpen && line.startsWith("• ")) {
                html.append("<div style=\"margin:0;padding:7px 0;border-bottom:1px solid #e8edf3;font-size:15px;line-height:1.7;overflow-wrap:anywhere;word-break:break-word\">")
                        .append(submissionEntry(line.substring(2))).append("</div>");
            } else {
                html.append("<p style=\"font-size:16px;line-height:1.8;overflow-wrap:anywhere\">").append(linked(line)).append("</p>");
            }
        }
        if (submissionCardOpen) html.append("</div>");
        if (sectionOpen) html.append("</td></tr></table></td></tr></table>");
        return html.toString();
    }

    private static ScorePalette scorePalette(ReportOpportunityPresentation.Header header) {
        for (String tag : header.legacyTags()) {
            var match = OPPORTUNITY_SCORE.matcher(tag);
            if (!match.matches()) continue;
            int score = Integer.parseInt(match.group(1));
            if (score > 100) return ScorePalette.NEUTRAL;
            if (score >= 75) return ScorePalette.HIGH;
            return score >= 40 ? ScorePalette.NORMAL : ScorePalette.NEUTRAL;
        }
        return ScorePalette.NEUTRAL;
    }
    private static boolean auxiliaryNote(String line) {
        return line.startsWith("안내:") || line.startsWith("미확인 항목:");
    }

    private static String summaryValues(String value, boolean omitSchedule, List<ReportSchedulePresentation.Fact> amounts,
                                        List<ReportSchedulePresentation.Fact> schedule) {
        var paragraphs = ReportEmailSummary.lines(value, omitSchedule, amounts, schedule);
        if (paragraphs.isEmpty()) return "";
        var html = new StringBuilder(sectionStart("summary", "핵심 요약"))
                .append("<tr><td style=\"padding:16px 16px 6px\">");
        for (String paragraph : paragraphs) {
            html.append("<p data-report-summary-item style=\"margin:0 0 10px;font-size:16px;line-height:1.8;color:#243247;word-break:keep-all;overflow-wrap:anywhere\">")
                    .append(linked(paragraph)).append("</p>");
        }
        return html.append("</td></tr></table></td></tr></table>").toString();
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

    private static String factRow(ReportSchedulePresentation.Fact fact, boolean showLabel, boolean compact) {
        String value = fact.value();
        boolean unknown = value.contains("원문 확인") || value.contains("원문 표에서")
                || value.contains("명시되지 않았") || ReportFactPresentation.unreadableTable(value);
        return "<tr data-report-fact=\"" + ReportBodyFormatter.html(fact.label())
                + "\"><td style=\"padding:16px;background:#ffffff;border-bottom:1px solid #e1e8f1\">"
                + (showLabel ? "<div style=\"margin-bottom:8px;font-size:14px;color:#475569;font-weight:700\">"
                + ReportBodyFormatter.html(fact.label()) + "</div>" : "")
                + "<div style=\"font-size:16px;line-height:1.7;overflow-wrap:anywhere;color:#243247;"
                + (fact.deadline() && !unknown ? "font-weight:700;" : "")
                + "\">" + factValues(fact.label(), value, compact) + "</div></td></tr>";
    }

    private static final Pattern ROLE_LABEL = Pattern.compile(
            "^((?:국내\\s*|해외\\s*|국외\\s*)?(?:주관기관\\s*외\\s*필수\\s*참여기관|(?:주관|공동|참여)(?:연구개발)?기관|필수\\s*참여기관))\\s*[:：]\\s*([\\s\\S]*)$");

    private static String factValues(String label, String value, boolean allowCompact) {
        var result = new StringBuilder();
        if (label.equals("금액·규모")) {
            for (String paragraph : ReportAmountPresentation.paragraphs(value)) {
                result.append("<div data-report-fact-item data-report-amount-paragraph style=\"margin:0 0 8px;line-height:1.8;word-break:keep-all;overflow-wrap:anywhere\">")
                        .append(linked(paragraph)).append("</div>");
            }
            return result.toString();
        }
        boolean compact = allowCompact && ReportEmailCompactPresentation.supports(label);
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
