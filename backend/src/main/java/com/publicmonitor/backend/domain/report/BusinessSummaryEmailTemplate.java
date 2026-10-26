package com.publicmonitor.backend.domain.report;

import com.publicmonitor.backend.domain.email.EmailMessageContent;
import com.publicmonitor.backend.global.presentation.YearNotation;
import java.util.List;

/** Static email tables: no scripts, disclosures, remote assets, or additional summarization. */
public final class BusinessSummaryEmailTemplate {
    private static final String TEXT = "font-family:Arial,'Malgun Gothic',sans-serif;line-height:1.75;overflow-wrap:anywhere;word-break:keep-all;";
    private BusinessSummaryEmailTemplate() {}

    public static EmailMessageContent render(String title, List<BusinessSummaryEmailView> notices, boolean missingRecords) {
        String heading = title == null ? "GovInsight 보고서" : YearNotation.display(title);
        String overview = overview(notices, missingRecords);
        var plain = new StringBuilder(heading + "\n" + overview + "\n");
        var html = new StringBuilder("""
                <!doctype html><html lang="ko"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#f4f6f8;color:#243247;font-family:Arial,'Malgun Gothic',sans-serif">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f4f6f8"><tr>
                <td align="center" style="padding:24px 10px">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:720px;table-layout:fixed">
                <tr><td data-report-header style="padding:28px 24px 24px;background:#193655;border:1px solid #2c4b6b;border-radius:14px">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
                <td style="color:#a9c9f3;font-size:16px;font-weight:700;letter-spacing:-0.4px">GovInsight</td>
                <td align="right" style="color:#a9c9f3;font-size:10px;font-weight:700;letter-spacing:1.5px">MONITORING REPORT</td>
                </tr></table>
                """);
        html.append("<h1 style=\"margin:22px 0 0;color:#ffffff;font-size:24px;font-weight:700;letter-spacing:-0.7px;line-height:1.5;word-break:keep-all;overflow-wrap:anywhere\">")
                .append(escape(heading)).append("</h1><div data-report-overview style=\"margin-top:20px;padding-top:16px;border-top:1px solid #3b5673;color:#c5d5e8;font-size:13px;line-height:1.9;overflow-wrap:anywhere\">")
                .append(escape(overview)).append("</div></td></tr>");
        for (int index = 0; index < notices.size(); index++) {
            var notice = notices.get(index);
            String score = notice.score() == null ? "기회점수 미산정" : "기회점수 " + notice.score() + "점";
            var palette = palette(notice.score());
            html.append("<tr><td style=\"padding-top:20px\"><table data-business-summary-card role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"table-layout:fixed;background:#ffffff;border:1px solid #cddbec;border-radius:14px\">")
                    .append("<tr><td data-report-card-heading style=\"padding:22px 20px;background:").append(palette.background())
                    .append(";border-top:4px solid ").append(palette.accent()).append(";border-radius:14px 14px 0 0\">")
                    .append("<p style=\"margin:0 0 10px;font-size:11px;font-weight:700;letter-spacing:1px;color:#4c709d\">공고 ")
                    .append(String.format("%02d", index + 1)).append("</p>")
                    .append("<h2 style=\"margin:0;color:#153957;font-size:22px;line-height:1.5;font-weight:700;overflow-wrap:anywhere;word-break:keep-all\">")
                    .append(escape(notice.title())).append("</h2>")
                    .append("<p data-report-institution style=\"margin:12px 0 0;color:#476581;font-size:14px;line-height:1.6;overflow-wrap:anywhere;word-break:keep-all\">")
                    .append(escape(notice.sourceOrganization())).append("</p><div style=\"margin-top:12px;line-height:2\">");
            badge(html, "score", score, palette.badge(), "#ffffff");
            badge(html, "change", notice.change(), "#ffffff", "#4d6480");
            html.append("</div></td></tr><tr><td style=\"padding:20px 16px 8px\"><table data-business-summary-table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"")
                    .append(" style=\"table-layout:fixed;border:1px solid #d5e1ee;border-collapse:separate;border-spacing:0;border-radius:10px;background:#ffffff;\">");
            row(html, "사업 유형", "<strong>" + escape(notice.type()) + "</strong>", false, true);
            String organizations = notice.organizations().isEmpty() ? escape("확인 필요")
                    : notice.organizations().stream().map(BusinessSummaryEmailTemplate::organization)
                        .collect(java.util.stream.Collectors.joining("<div style=\"height:10px;line-height:10px\">&#8203;</div>"));
            row(html, "사업 담당기관", organizations, false, false);
            row(html, "사업명", "<strong>" + escape(notice.title()) + "</strong>", false, false);
            row(html, "지원규모", escape(notice.supportScale()), true, false);
            row(html, "사업 설명", escape(notice.purpose()), false, false);
            row(html, "제출기한", escape(notice.deadline()), true, false);
            html.append("</table>");
            if (BusinessSummaryEmailView.safeUrl(notice.originalUrl()) != null) {
                html.append("<div style=\"margin:14px 0 16px\">")
                        .append(originalButton(notice.originalUrl())).append("</div>");
            }
            html.append("</td></tr></table></td></tr>");
            plain.append("\n공고 ").append(String.format("%02d", index + 1)).append(" · ").append(score).append(" · ").append(notice.change()).append("\n")
                    .append("공공기관: ").append(notice.sourceOrganization()).append("\n")
                    .append("사업 유형: ").append(notice.type()).append("\n사업 담당기관: ");
            plain.append(notice.organizations().isEmpty() ? "확인 필요"
                    : notice.organizations().stream().map(organization -> organization.role() + " · " + organization.name())
                        .collect(java.util.stream.Collectors.joining("\n")));
            plain.append("\n사업명: ").append(notice.title()).append("\n지원규모: ").append(notice.supportScale())
                    .append("\n사업 설명: ").append(notice.purpose()).append("\n제출기한: ").append(notice.deadline()).append("\n");
            if (BusinessSummaryEmailView.safeUrl(notice.originalUrl()) != null) plain.append("공고 원문: ").append(notice.originalUrl()).append("\n");
        }
        html.append("<tr><td style=\"padding:24px 16px;color:#65788f;font-size:12px;line-height:1.8;text-align:center\">")
                .append("GovInsight · 공공기관 모니터링 보고서</td></tr></table></td></tr></table></body></html>");
        return new EmailMessageContent(YearNotation.display(plain.toString()), html.toString());
    }

    private static String overview(List<BusinessSummaryEmailView> notices, boolean missingRecords) {
        if (notices.isEmpty()) return missingRecords
                ? "이 보고서의 공고 정보를 확인하지 못했습니다." : "이번 실행에서 확인된 공고가 없습니다.";
        long fresh = notices.stream().filter(notice -> "신규".equals(notice.change())).count();
        long updated = notices.stream().filter(notice -> "변경".equals(notice.change())).count();
        long unchanged = notices.stream().filter(notice -> "변경 없음".equals(notice.change())).count();
        long unknown = notices.size() - fresh - updated - unchanged;
        return "신규 " + fresh + "건  |  수정 " + updated + "건  |  변경 없음 " + unchanged + "건"
                + (unknown == 0 ? "" : "  |  상태 미확인 " + unknown + "건");
    }

    private record Palette(String background, String accent, String badge) {}

    private static Palette palette(Integer score) {
        if (score != null && score >= 75) return new Palette("#fef2f2", "#dc2626", "#b91c1c");
        if (score != null && score >= 40) return new Palette("#e7effb", "#357bd8", "#215fa8");
        return new Palette("#f1f5f9", "#94a3b8", "#475569");
    }

    private static void badge(StringBuilder html, String kind, String text, String background, String color) {
        html.append("<span data-report-").append(kind).append(" style=\"display:inline-block;vertical-align:middle;margin:0 6px 4px 0;padding:3px 7px;border-radius:5px;font-size:12px;line-height:1.6;font-weight:700;background:")
                .append(background).append(";color:").append(color).append("\">").append(escape(text)).append("</span>");
    }

    private static void row(StringBuilder html, String label, String value, boolean emphasis, boolean first) {
        String border = first ? "" : "border-top:1px solid #e1e9f3;";
        html.append("<tr data-business-summary-row><th scope=\"row\" width=\"92\" align=\"left\" valign=\"top\" style=\"width:92px;padding:15px 10px;")
                .append(TEXT).append(border).append("background:").append(emphasis ? "#f0f5fd" : "#f5f8fc")
                .append(";font-size:13px;font-weight:600;color:#4b688b\">").append(escape(label))
                .append("</th><td valign=\"top\" style=\"padding:15px 12px;").append(TEXT).append(border)
                .append("font-size:14px;color:").append(emphasis ? "#1760ad;font-weight:700" : "#294e75")
                .append(";background:").append(emphasis ? "#fbfdff" : "#ffffff").append("\">").append(value).append("</td></tr>");
    }

    private static String organization(BusinessSummaryEmailView.Organization organization) {
        var text = new StringBuilder("<span style=\"display:inline-block;background:#edf3fa;color:#6581a2;padding:1px 5px;border-radius:3px;font-size:11px;margin-right:6px\">")
                .append(escape(organization.role())).append("</span><strong>").append(escape(organization.name())).append("</strong>");
        return text.toString();
    }

    private static String originalButton(String url) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td align=\"center\" bgcolor=\"#1c426b\" style=\"border-radius:6px;background:#1c426b\">"
                + "<a data-report-original-link href=\"" + attribute(url) + "\""
                + " style=\"display:block;padding:12px 16px;border-radius:6px;background:#1c426b;color:#ffffff;"
                + "font-size:14px;font-weight:700;line-height:20px;text-align:center;text-decoration:none;"
                + "font-family:Arial,'Malgun Gothic',sans-serif\">공고 원문 보기</a></td></tr></table>";
    }

    private static String escape(String value) {
        return attribute(YearNotation.display(value == null ? "" : value)).replace("\r\n", "\n").replace("\n", "<br>");
    }

    private static String attribute(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
