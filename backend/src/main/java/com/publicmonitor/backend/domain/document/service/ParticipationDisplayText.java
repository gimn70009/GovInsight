package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import java.util.regex.Pattern;

/** Display projection only. Evidence remains verbatim, and uncertain table cells are never guessed. */
final class ParticipationDisplayText {
    private static final String MARK = "(?:[가나다라마바사아자차카타파하][.]|\\d{1,3}[.)](?!\\d)|\\(\\d{1,3}\\)|[①-⑳➀-➉]|[□■○◦●•⦁▪∘※*ㆍ・·]|-(?=\\s|[가-힣]))";
    private static final Pattern LEGAL = Pattern.compile("[<〈]\\s*([^>〉]{2,70}(?:법|법률))\\s*[>〉]");
    private static final Pattern COMPLETE = Pattern.compile("(?s).*니다[.](?:\\s*\\([^\\n]+\\)[.]?)?$");
    private ParticipationDisplayText() {}

    static List<ParticipationRule> prepare(List<ParticipationRule> rules) {
        var result = new ArrayList<ParticipationRule>();
        for (var rule : rules) {
            if (rule.section().equals("REVIEW")) { result.add(rule); continue; }
            var inputs = new ArrayList<String>();
            // Undo earlier length-only summaries using the complete saved display input.
            rule.details().stream().filter(value -> value.startsWith("적용 기준\n"))
                    .map(value -> value.substring("적용 기준\n".length())).forEach(inputs::add);
            if (inputs.isEmpty()) inputs.add(rule.text());
            List<String> details = ParticipationClauseText.joinWrappedParentheses(rule.details()).stream().filter(value -> !value.startsWith("적용 기준\n"))
                    .filter(value -> !value.contains("[표]") && !garbledTable(value))
                    .flatMap(value -> split(value).stream()).map(ParticipationDisplayText::sentence).filter(value -> !value.isBlank()).toList();
            for (String input : inputs) {
                var parts = split(input);
                if (parts.isEmpty() && (garbledTable(input) || ParticipationReadability.damagedWord(input))) {
                    result.add(new ParticipationRule("REVIEW", rule.section().equals("FUNDING") ? "자부담 능력" : rule.label(),
                            "조건은 원문 근거에서 확인해야 합니다.", null, null, List.of(), List.of(), List.of(), List.of(),
                            rule.sources(), garbledTable(input) ? ParticipationReviewReason.TABLE_MAPPING_UNCLEAR : ParticipationReviewReason.INCOMPLETE_TEXT));
                }
                for (String part : parts) {
                    String text = sentence(subtitle(part, rule));
                    if (text.isBlank()) continue;
                    result.add(new ParticipationRule(rule.section(), rule.label(), text, cleanNullable(rule.scope()), cleanNullable(rule.audience()),
                            details, rule.exceptions(), rule.guidance(), rule.outcomes(), rule.sources(), rule.reviewReason()));
                }
            }
        }
        return List.copyOf(result);
    }

    private static String subtitle(String part, ParticipationRule rule) {
        var headings = new ArrayList<String>();
        headings.add(rule.label());
        if (rule.scope() != null) headings.add(rule.scope());
        rule.sources().stream().map(RequirementSource::sectionTitle).filter(Objects::nonNull).forEach(headings::add);
        if (part.matches("(?s).*산재보험 가입\\s*코드와 일치.*") && !part.contains("고위험업종")
                && headings.stream().anyMatch(value -> value.matches(".*고위험업종.*"))) return "(고위험업종 유형) " + part;
        if (part.strip().startsWith("사업비 매칭지원") && headings.stream().anyMatch(value -> value.equals("지원방식")))
            return "지원방식: " + part;
        return part;
    }

    static boolean readable(ParticipationCriterionEntry entry) {
        return entry.rule().section().equals("REVIEW") || !entry.rule().outcomes().isEmpty()
                || COMPLETE.matcher(entry.rule().text()).matches();
    }

    static boolean multipleTypes(List<ParticipationRule> rules) {
        // Reconstructed rows also prove distinct types when the surrounding prose was not extracted.
        long tableTypes = rules.stream().map(ParticipationRule::text)
                .filter(text -> text.matches(".{2,80} 유형의 (?:지원 규모|국비 지원한도).+"))
                .map(text -> text.substring(0, text.indexOf(" 유형의 "))).distinct().count();
        if (tableTypes > 1) return true;
        return rules.stream().flatMap(rule -> rule.sources().stream()).map(RequirementSource::excerpt).filter(Objects::nonNull)
                .anyMatch(value -> value.matches("(?s).*\\([^)]{1,30}\\)\\s*[,·]\\s*[^\\n]{1,40}\\([^)]{1,30}\\)\\s*중\\s*1개.*"));
    }

    static ParticipationCriterionEntry qualify(ParticipationCriterionEntry entry, boolean multipleTypes) {
        var rule = entry.rule();
        String text = rule.text();
        if (multipleTypes && !rule.section().equals("REVIEW") && !typed(text) && generic(rule.scope()) && generic(rule.audience())
                && !text.startsWith("모든 유형 공통으로") && !text.contains("유형별") && !text.contains("원문 근거"))
            text = "모든 유형 공통으로 " + text;
        return new ParticipationCriterionEntry(new ParticipationRule(rule.section(), rule.label(), text, rule.scope(), rule.audience(),
                rule.details(), rule.exceptions(), rule.guidance(), rule.outcomes(), rule.sources(), rule.reviewReason()),
                entry.context(), entry.relation(), entry.relatedCriteria());
    }

    static boolean legalCategory(String text) {
        return text.endsWith("소상공인기본법상 소상공인에 해당해야 합니다.");
    }

    static String context(String value) {
        if (value == null) return null;
        String cleaned = String.join(" ", split(value).stream().map(ParticipationDisplayText::sentence).filter(part -> !part.isBlank()).toList());
        return cleaned.isBlank() ? null : cleaned;
    }

    private static boolean generic(String value) {
        return value == null || value.isBlank() || value.matches("(?s).*(?:지원제외 대상|구분 제외 대상|공통|신청조건).*");
    }

    private static boolean typed(String value) {
        return value.matches("(?s).*(?:유형\\s*\\d|유형으로|유형은|유형의|유형에서|유형에|\\([^)]*유형\\)|고위험업종|일반 유형).*");
    }

    static String cleanNullable(String value) {
        if (value == null) return null;
        return String.join("\n", value.lines().map(ParticipationDisplayText::clean).toList());
    }

    static String clean(String value) {
        String text = value.replace('\u00a0', ' ').replaceAll("[\\p{Co}\\u200B\\uFEFF]", "")
                .replaceAll("\\s+", " ").strip();
        // Reference-only parentheses may go; numeric/legal qualifiers in ordinary parentheses stay.
        text = text.replaceAll("\\((?:\\s*\\[(?:참고|붙임|별표|별첨)\\s*\\d+\\]\\s*[,，]?\\s*)+(?:참조|참고)?\\s*\\)", "")
                .replaceAll("\\(\\s*\\d+\\s*[pP쪽]\\s+[^()]{0,30}(?:참조|참고)\\s*\\)", "")
                .replaceAll("\\[(?:참고|붙임|별표|별첨)\\s*\\d+\\]", "")
                .replaceAll("[<〈]\\s*(?:별표|참고|붙임|별첨)\\s*\\d+\\s*[>〉]", "")
                .replaceAll("\\(\\s*(?:참조|참고)?\\s*\\)", "")
                .replaceAll("[・ㆍ·]{2,}", "")
                .replaceAll("^[\\s]+", "");
        String previous;
        do {
            previous = text;
            text = text.replaceFirst("^" + MARK + "\\s*", "")
                    .replaceFirst("\\s+" + MARK + "\\s*$", "").strip();
        } while (!text.equals(previous));
        return text.replaceAll("[①-⑳➀-➉]", "")
                .replaceAll("\\s+[<〉>]\\s*$", "")
                .replaceAll("(합|입|됩|있습|없습)\\s+니다", "$1니다")
                .replaceAll("상시\\s*근로자\\s*수", "상시근로자 수")
                .replaceAll("상시근로자 수\\s*등이", "상시근로자 수가")
                .replaceAll("신청\\s+영위", "영위")
                .replaceAll("영위\\s*중이어야", "영위하고 있어야")
                .replaceAll("한국표준산업분류\\s+상", "한국표준산업분류상")
                .replaceAll("\\s+조건[.]?$", "")
                .replaceAll("\\s+", " ").strip();
    }

    static boolean garbledTable(String value) {
        return value.contains("[표]") || value.contains("[/표]")
                || value.matches("(?s).*(?:지원\\s*한도|지원\\s*유형).*(?:세부\\s*지원\\s*내용|지원\\s*규모).*(?:국비|자부담).*")
                || value.matches("(?s).*\\d[\\d,]*(?:내외|이내|이상)(?:개사|만원|억원).*")
                || value.matches("(?s).*(?:지원유형|세부지원내용).*\\d.*\\d.*");
    }

    static List<String> split(String value) {
        if (garbledTable(value) || ParticipationReadability.damagedWord(value)) return List.of();
        var boundary = Pattern.compile("\\s+(?=(?:[가나다라마바사아자차카타파하][.]|\\(\\d{1,3}\\)|[①-⑳])\\s*[가-힣「｢<])").matcher(value);
        var markedBuilder = new StringBuilder();
        int position = 0;
        while (boundary.find()) {
            if (ParticipationClauseText.inside(value, boundary.start())) continue;
            markedBuilder.append(value, position, boundary.start()).append('\n');
            position = boundary.end();
        }
        String marked = markedBuilder.append(value.substring(position)).toString();
        var parts = new ArrayList<String>();
        var legal = LEGAL.matcher(marked);
        int start = 0;
        while (legal.find()) {
            if (legal.start() > start) parts.addAll(ParticipationExecutionClauses.split(marked.substring(start, legal.start())));
            String law = legal.group(1).strip();
            String definition = marked.substring(legal.end());
            if (law.replaceAll("\\s+", "").equals("소상공인기본법") && definition.contains("정의"))
                parts.add("소상공인기본법상 소상공인에 해당해야 합니다.");
            // Definitions explain a legal category; their full text remains in source evidence.
            start = legal.end();
        }
        if (start < marked.length()) {
            String rest = marked.substring(start);
            for (String line : rest.split("\\n")) parts.addAll(ParticipationExecutionClauses.split(line));
        }
        return parts;
    }

    static String sentence(String value) {
        if (garbledTable(value) || ParticipationReadability.damagedWord(value)) return "";
        String text = clean(value);
        if (text.isBlank() || text.matches("(?s)^(?:제\\d+조\\(정의\\)|이 법에서|.*이란 다음 각).*(?:말한다|말합니다|같다|같습니다)[.]?$")) return "";
        text = text.replaceFirst("[.]$", "").strip();
        var matching = Pattern.compile("^지원방식\\s*[:：]\\s*(.+?)\\s*\\(국비\\s*(\\d+(?:[.]\\d+)?%\\s*(?:이내|이하)),\\s*자부담\\s*(\\d+(?:[.]\\d+)?%\\s*이상)\\)$").matcher(text);
        if (matching.matches()) return "지원방식은 " + matching.group(1) + "이며, 국비는 " + matching.group(2) + ", 자부담은 " + matching.group(3) + "입니다.";
        if (text.matches("^(?=.*고위험업종)(?:\\(고위험업종 유형\\)\\s*)?(?:고위험업종\\s*)?산재보험 가입\\s*코드와 일치\\s*(?:필요|필요합니다)$"))
            return "고위험업종 유형으로 신청하려면 산재보험 가입 코드가 고위험업종 코드와 일치해야 합니다.";
        text = text.replaceFirst("^\\(([^()]+) 유형\\)\\s*", "$1 유형으로 신청하려면 ")
                .replaceFirst("상시근로자 수\\s*(\\d+)인 미만의 소상공인$", "상시근로자 수가 $1명 미만인 소상공인이 대상입니다")
                .replaceFirst("상시근로자 수가 (\\d+)인 미만", "상시근로자 수가 $1명 미만")
                .replaceFirst("에 따른 소상공인일 것$", "상 소상공인에 해당해야 합니다")
                .replaceFirst("일 것$", "이어야 합니다")
                .replaceFirst("할 것$", "해야 합니다")
                .replaceFirst("하여야 함$", "해야 합니다")
                .replaceFirst("야 함$", "야 합니다")
                .replaceFirst("할 수 있음$", "할 수 있습니다")
                .replaceFirst("할 수 없음$", "할 수 없습니다")
                .replaceFirst("영위중$", "영위하고 있어야 합니다");
        text = text.replaceAll("\\((?:붙임|참고|별표)\\s*\\d+\\s*(?:참고|참조)?\\)", "")
                .replaceFirst("가점을 부여함$", "가점을 부여합니다")
                .replaceFirst("판단 가능$", "판단할 수 있습니다")
                .replaceFirst("인정$", "인정됩니다")
                .replaceFirst("제외$", "제외됩니다")
                .replaceFirst("연계 지원$", "연계 지원합니다")
                .replaceFirst("^(.+?)\\s+우대$", "$1은 우대 대상입니다")
                .replaceFirst("기업의 부도$", "기업이 부도 상태인 경우입니다");
        var score = Pattern.compile("^(.+?)\\s+(?:·\\s*)?(\\d+(?:[.]\\d+)?점)\\s*(가점|감점)(?:\\s*부여)?$").matcher(text);
        if (score.matches()) return score.group(1) + " 항목은 " + score.group(2) + " " + score.group(3) + "입니다.";
        if (text.matches("^\\(감점 합계 .*한도\\)$")) return text.substring(1, text.length() - 1) + "입니다.";
        text = text.replaceAll("[「｢]소상공인기본법[」｣]\\s*제2조(?:에 따른|상)?\\s*소상공인", "소상공인기본법상 소상공인");
        text = ParticipationExecutionClauses.display(text);
        if (COMPLETE.matcher(text).matches()) {
            var tail = Pattern.compile("^(.*니다[.])\\s*(\\([^\\n]+\\))[.]?$").matcher(text);
            return tail.matches() ? tail.group(2) + " " + tail.group(1) : text;
        }
        // Complete noun predicates only when their meaning is explicit; never append arbitrary eligibility.
        text = text.replaceFirst("[.]$", "");
        if (text.matches(".*(?:이상|이하|이내|미만|초과|대상|해당|가능|불가|필수|필요|보유|달성|소재|잠식|중|기업|기관|소상공인|소공인|업|법인|기준)$")) {
            if (text.endsWith("일치 필요")) return text.replaceFirst("일치 필요$", "일치해야 합니다.");
            if (text.endsWith("보유")) return text + "해야 합니다.";
            if (text.endsWith("달성")) return text + "해야 합니다.";
            if (text.endsWith("잠식")) return text + "에 해당하는 경우입니다.";
            return text + "입니다.";
        }
        // A connected parenthetical qualifier belongs before the final predicate, not after a cut-off sentence.
        var qualified = Pattern.compile("^(.*?)(\\([^()]+\\))$").matcher(text);
        if (qualified.matches() && qualified.group(1).matches(".*(?:기업|기관|소상공인|소공인|자본전액잠식)\\s*"))
            return text + "에 해당하는 경우입니다.";
        int parenthesis = text.indexOf('(');
        if (parenthesis > 0 && ParticipationClauseText.balance(text) == 0) {
            String subject = text.substring(0, parenthesis).strip();
            if (subject.matches(".*(?:기관|기업|소공인|소상공인)$")) return text + "이 대상입니다.";
            if (subject.endsWith("자본전액잠식")) return text + "에 해당하는 경우입니다.";
            if (text.matches("(?s).*연구 자금 지원 \\([^()]+\\)$")) return text + "을 받을 수 있습니다.";
        }
        return text + "."; // Later context fallback retains linked predicates; no character-count truncation.
    }

    static String complete(String text, String context) {
        String display = sentence(text);
        if (!COMPLETE.matcher(display).matches() && context != null && !garbledTable(context)) {
            String full = sentence(context);
            if (COMPLETE.matcher(full).matches()) return full;
        }
        return display;
    }

    static boolean simpleHeadcount(String text) {
        return text.matches("(?:모든 유형 공통으로 )?상시근로자 수가 \\d+명 미만(?:인 소상공인이 대상입니다|이어야 합니다)[.]");
    }

    static String identity(String text) {
        return text.replaceFirst("상시근로자 수가 (\\d+)명 미만인 소상공인이 대상입니다[.]$", "상시근로자 수가 $1명 미만이어야 합니다.").replaceAll("[\\s'‘’“”\"「」｢｣]", "");
    }
}
