package com.publicmonitor.backend.domain.document.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Splits only at grammatical boundaries; a topic's character offset alone is never a boundary. */
final class ParticipationClauseText {
    private static final Pattern JOIN = Pattern.compile(
            "[,;]\\s+|(?:이고|이며|하고|하며|로서|보유한|달성한|이상의|이하의|미만의|초과의)\\s+|\\s+(?:및|또는|혹은|있는)\\s+");
    private static final Pattern VALUE_END = Pattern.compile(
            "(?s).*(?:이상|이하|이내|미만|초과|무관|경과|보유|달성|증가|감소|신장|인증)(?:의)?\\s*$");
    private ParticipationClauseText() {}

    static int boundary(String text, int previousEnd, int nextStart) {
        int boundary = -1;
        var match = JOIN.matcher(text);
        match.region(previousEnd, nextStart);
        while (match.find()) {
            if (inside(text, match.start())) continue;
            if (Character.isDigit(text.charAt(Math.max(0, match.start() - 1)))
                    && match.end() < text.length() && Character.isDigit(text.charAt(match.end()))) continue;
            boundary = match.end();
        }
        if (boundary >= 0) return boundary;
        if (nextStart > 0 && Character.isWhitespace(text.charAt(nextStart - 1))
                && VALUE_END.matcher(text.substring(0, nextStart)).matches()) return nextStart;
        return -1;
    }

    static String fragment(String original, int start, int end) {
        String text = original.substring(start < 0 ? 0 : start, end < 0 ? original.length() : end).strip();
        if ((start <= 0 && (end < 0 || end == original.length()))) return original;
        text = text.replaceFirst("^[,;·•\\s]+", "").replaceFirst("^(?:및|또는|혹은|있는)\\s+", "")
                .replaceFirst("[,;\\s]+$", "");
        // Retain the predicate, rather than deleting it along with the coordinating ending.
        text = text.replaceFirst("있으며$", "있습니다.")
                .replaceFirst("(?:하고|하며)$", "")
                .replaceFirst("(?:이며|이고|이면서)$", "입니다.")
                .replaceFirst("(이상|이하|미만|초과)의$", "$1")
                .replaceFirst("(보유|달성|증가|감소|신장|완제|수료|제조)한?$", "$1")
                .replaceFirst("제조하는$", "제조")
                .strip();
        if (text.isBlank() || balance(text) != 0 || text.matches("(?s).*(?:으로 인해|위해|대비|기업의|기관의|업종이|로서|여야|하여야|해야|또는|거나|중인|중이거나|후|경우|등에|\\s및)$"))
            return original;
        if (text.matches("(?s).*(?:증가한|감소한|적은|이상인|이하인|청년 근로자.*인)$")) return original;
        if (text.matches("(?s).*(?:은|는|으로|의|를|을|에서|에게)$")) return original;
        if (text.matches("(?s).*(?:보유|경과)$") && original.matches("(?s).*(?:대상입니다|여야 합니다|해야 합니다).*"))
            return text + "해야 합니다.";
        if (text.matches("(?s).*(?:보유|제조|경과)$")) return text + " 조건입니다.";
        if (text.matches("(?s).*(?:은|는)\\s+.+소재$")) return text + " 조건입니다.";
        return text;
    }

    static boolean inside(String text, int position) {
        int depth = 0;
        for (int i = 0; i < position; i++) {
            if ("(（「『｢".indexOf(text.charAt(i)) >= 0) depth++;
            if (")）」』｣".indexOf(text.charAt(i)) >= 0) depth = Math.max(0, depth - 1);
        }
        return depth > 0;
    }

    static int balance(String text) {
        int depth = 0;
        for (char c : text.toCharArray()) {
            if ("(（".indexOf(c) >= 0) depth++;
            if (")）".indexOf(c) >= 0) depth--;
        }
        return depth;
    }

    static List<String> joinWrappedParentheses(List<String> pieces) {
        var result = new ArrayList<String>();
        for (String piece : pieces) {
            if (!result.isEmpty() && balance(result.getLast()) > 0)
                result.set(result.size() - 1, result.getLast() + " " + piece);
            else result.add(piece);
        }
        return result;
    }

    static boolean caption(String text) {
        return text.matches("(?s)^(?:\\(예시\\)|예시[:：]).*")
                || text.matches("(?s).*(?:과제 관련|기업이란\\?|기준이란\\?|세부 기준이 있습니다)[.]?$");
    }

    static boolean administrative(String text) {
        String value = text.replaceAll("\\s+", " ");
        if (value.matches(".*(?:접수|신청)\\s*(?:마감|기간|일정|처).*")
                && !value.matches(".*(?:제출해야|제출하여야|제출.*필수|첨부.*필수|참여해야|보유|업력|기업|제외|불가).*")) return true;
        return value.matches(".*(?:통합Ezbaro|통합RCMS|연구비통합관리|사용[・·/]?관리|사용・관리).*")
                && !value.matches(".*(?:자부담|현금).*(?:비율|%|이상|면제).*");
    }
}
