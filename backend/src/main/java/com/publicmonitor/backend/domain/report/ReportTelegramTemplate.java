package com.publicmonitor.backend.domain.report;

import java.util.ArrayList;
import java.util.List;

/** Telegram-only projection; the saved report and email retain complete summaries. */
public final class ReportTelegramTemplate {
    private static final int LIMIT = 4096;
    private static final String SEPARATOR = "\n\n────────────────\n\n";

    private ReportTelegramTemplate() {}

    public static String render(String title, String body) {
        String original = title + "\n\n" + body;
        if (fits(original)) return original;
        // Only project structured reports. Let the caller reject oversized legacy prose.
        String[] sections = body.replace("\r\n", "\n").split("(?m)(?=^▸ )");
        if (sections.length < 2) return original;
        List<String> blocks = new ArrayList<>();
        blocks.add(title + "\n\n" + clean(sections[0]));
        List<String> cards = new ArrayList<>();
        for (int i = 1; i < sections.length; i++) {
            cards.add(clean(sections[i]).replaceAll("(?m)^요약: [^\n]*",
                    "요약: 요약 전문은 이메일 보고서 또는 게시글 상세에서 확인해 주세요."));
        }
        for (int i = 0; i < cards.size(); i++) {
            int remaining = cards.size() - i - 1;
            String candidate = String.join(SEPARATOR, blocks) + SEPARATOR + cards.get(i);
            String tail = remaining == 0 ? "" : SEPARATOR + omitted(remaining);
            if (!fits(candidate + tail)) {
                blocks.add(omitted(cards.size() - i));
                break;
            }
            blocks.add(cards.get(i));
        }
        String projected = String.join(SEPARATOR, blocks);
        return fits(projected) ? projected : original;
    }

    private static String clean(String block) {
        return block.replaceAll("(?m)^─{3,}[ \\t]*$", "").strip();
    }

    private static String omitted(int count) {
        return "그 외 " + count + "건은 이메일 보고서 또는 보고서 상세에서 확인해 주세요.";
    }

    private static boolean fits(String value) {
        return ReportBodyFormatter.displayLength(value) <= LIMIT;
    }
}
