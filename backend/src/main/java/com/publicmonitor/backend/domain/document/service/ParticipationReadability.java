package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Display-only cleanup and progressive disclosure; original evidence is never rewritten. */
final class ParticipationReadability {
    private static final String ARTICLE = "(?:제\\d+조(?:의\\d+)?(?:제?\\d+[항호목])*|같은\\h*조\\h*(?:제?\\d+[항호목])+)";
    private static final Pattern LEGAL_MODIFIER = Pattern.compile(
            "(?:[｢「『\\[][^｢「『\\[｣」』\\]\\n]{2,120}[｣」』\\]]\\h*)?" + ARTICLE
                    + "(?:\\h*(?:또는|및)\\h*" + ARTICLE + ")*\\h*에\\h*(?:따른|따라|해당하는)\\h*+(?!사유|기준|요건|조건|규정)");
    private ParticipationReadability() {}

    record Explained(String text, String legal) {}

    static Explained explainLegal(String value) {
        String readable = clean(value);
        String simplified = LEGAL_MODIFIER.matcher(readable).replaceAll("").strip();
        return new Explained(simplified, simplified.equals(readable) ? null : readable);
    }

    static String clean(String value) {
        return value.replaceAll("[\\p{Co}\\uFFFD\\u200B\\uFEFF]", "")
                .replace('\u00a0', ' ').replaceAll("[‧ㆍ․]", "·")
                .replaceAll("(?m)^\\h*[□■∘○◦※*]+\\h*", "")
                .replaceAll("\\h+", " ").strip();
    }

    static String clause(String value) {
        String text = clean(ParticipationConditionText.unmark(value)).replaceFirst("[.]$", "");
        if (text.isBlank()) return text;
        text = text.replace("必", "필수").replaceAll("(가점|감점)\\h+부여", "$1을 부여");
        text = text.replaceFirst("제출\\h*필수\\(미제출\\h*시\\h*불인정\\)$", "제출해야 합니다. 제출하지 않으면 인정되지 않습니다")
                .replaceFirst("부여\\h*가능\\((.+)\\)$", "부여할 수 있습니다. ($1)")
                .replaceFirst("실적이\\h*있는\\h*자$", "실적이 있는 경우")
                .replaceFirst("보유한\\h*자$", "보유한 경우");
        text = text.replaceFirst("할\\h*수\\h*있음$", "할 수 있습니다")
                .replaceFirst("할\\h*수\\h*없음$", "할 수 없습니다")
                .replaceFirst("경우만\\h*해당$", "경우에만 인정됩니다")
                .replaceFirst("한정함$", "한정됩니다")
                .replaceFirst("인정함$", "인정합니다")
                .replaceFirst("따름$", "따릅니다")
                .replaceFirst("지정$", "지정합니다")
                .replaceFirst("부여\\h*가능$", "부여할 수 있습니다")
                .replaceFirst("야\\h*함$", "야 합니다")
                .replaceFirst("이전\\h*완료\\h*필요$", "이전을 완료해야 합니다")
                .replaceFirst("필요$", "필요합니다")
                .replaceFirst("않음$", "않습니다")
                .replaceFirst("아님$", "아닙니다")
                .replaceFirst("됨$", "됩니다")
                .replaceFirst("없음$", "없습니다")
                .replaceFirst("함$", "합니다");
        if (text.matches(".*(?:합니다|됩니다|습니다|입니다|하세요)$")) return text + ".";
        if (text.endsWith("경우")) return text + "입니다.";
        if (text.matches(".*(?:인증서|확인서|지정서|증명서|증빙|서류|표창장)(?: 등)?$") || text.endsWith(" 등")) {
            return text + (hasFinalConsonant(text) ? "을" : "를") + " 확인해야 합니다.";
        }
        if (text.matches(".*(?:기업|기관|제품|연구개발과제|자)$")) return text + (hasFinalConsonant(text) ? "이" : "가") + " 대상입니다.";
        return ParticipationConditionText.displaySentence(text, null);
    }

    static String evidenceClause(String value) {
        String text = clean(value).replaceFirst("[.]$", "");
        if (text.matches(".*(?:기업|기관|제품|연구개발과제|자)$")) {
            return text + (hasFinalConsonant(text) ? "을" : "를") + " 확인해야 합니다.";
        }
        return clause(value);
    }

    private static boolean hasFinalConsonant(String value) {
        char last = value.charAt(value.length() - 1);
        return last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
    }

    static boolean damagedWord(String text) {
        return text.matches("(?s).*[\\p{L}\\p{N}]\\uFFFD[\\p{L}\\p{N}].*");
    }

    static ParticipationRule present(ParticipationRule row) {
        if (row.section().equals("REVIEW")) return row;
        if (damagedWord(row.text() + " " + row.scope() + " " + row.audience() + " "
                + String.join(" ", row.details()) + " " + String.join(" ", row.exceptions()) + " " + String.join(" ", row.guidance()))) {
            return new ParticipationRule("REVIEW", row.label(), "조건에 포함된 문자를 확인해야 합니다.", null, null,
                    List.of(), List.of(), List.of(), row.outcomes(), row.sources(), ParticipationReviewReason.INCOMPLETE_TEXT);
        }
        var details = new ArrayList<>(row.details().stream().map(value -> value.contains("\n") || value.startsWith("법적 자격 기준:") ? clean(value) : clause(value)).toList());
        String text = row.text().contains("\n") ? clean(row.text()) : clause(row.text());
        // Keep every qualifier in the expanded panel rather than dumping an unbounded paragraph in the headline.
        if (!row.section().equals("REVIEW") && text.length() > 230 && !text.contains("\n• ")) {
            var sentences = text.split("(?<=니다[.])\\h+");
            if (sentences.length > 1 && sentences[0].length() >= 20 && sentences[0].length() <= 180) {
                text = sentences[0] + "\n추가 기준은 세부 조건에서 확인할 수 있습니다.";
            } else {
                text = row.label() + "에 관한 세부 기준이 있습니다.\n적용 대상과 인정 조건을 함께 확인해야 합니다.";
            }
            details.addFirst("적용 기준\n" + clean(row.text()));
        }
        return new ParticipationRule(row.section(), clean(row.label()), text,
                row.scope() == null ? null : clean(row.scope()), row.audience() == null ? null : clean(row.audience()),
                List.copyOf(details), row.exceptions().stream().map(ParticipationReadability::clause).toList(),
                row.guidance().stream().map(ParticipationReadability::clean).toList(), row.outcomes(), row.sources(), row.reviewReason());
    }
}
