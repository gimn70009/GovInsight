package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Organizes verified clauses by their effect on an application, without judging a company. */
final class ParticipationGuidePresentation {
    private static final Pattern TYPE_RULE = Pattern.compile(
            "^(.+?)(?:은|는)\\h*(유형\\h*[0-9A-Za-zⅠ-Ⅹ]+)(?:에\\h*한해|만)?\\h*신청(?:할\\h*수\\h*)?(가능|불가|있습니다|없습니다)[.]?$");
    private static final String EXCLUSIVE_TEXT = "해당 기업은 신청 대상으로 표시된 유형에 한해 신청할 수 있습니다.";
    private ParticipationGuidePresentation() {}

    static List<ParticipationRule> rules(List<ParticipationCondition> conditions) {
        var result = new LinkedHashMap<String, ParticipationRule>();
        var sharedScopes = conditions.stream()
                .filter(value -> value.kind() == ParticipationKind.MANDATORY && value.scope() == null)
                .map(value -> compact(value.text().replaceFirst("(?:이 대상입니다|입니다)[.]?$", ""))).toList();
        for (var condition : conditions) {
            ParticipationRule row = present(condition, sharedScopes);
            var identity = identity(row);
            var previous = result.get(identity);
            if (previous == null) result.put(identity, row);
            else result.put(identity, merge(previous, row));
        }
        return result.values().stream().sorted(Comparator.comparingInt(row -> order(row.section()))).toList();
    }

    private static ParticipationRule present(ParticipationCondition condition, List<String> sharedScopes) {
        var details = new ArrayList<String>();
        var exceptions = new ArrayList<String>();
        var guidance = new ArrayList<String>();
        boolean inGuidance = false, inExceptions = false;
        for (String line : condition.details()) {
            if (line.matches("^.{0,40}(?:확인방법|확인 방법|조회방법|조회 방법).*")) inGuidance = true;
            else if (inGuidance && !line.matches(".*(?:코드|HS.?부호|세번부호|헬프데스크|전화.?문의).*")) inGuidance = false;
            if (line.matches("^[<〈].*(?:예외|유예).*[>〉][.]?$")) {
                inExceptions = true;
                inGuidance = false;
                exceptions.add(line.replaceAll("^[<〈]\\h*|\\h*[>〉][.]?$", ""));
            } else if (inExceptions || (line.matches("^(?:단[,\\h]|다만|예외).*")
                    && !line.matches(".*(?:이어야|해야|필수).*"))) exceptions.add(line);
            else if (inGuidance) {
                for (String instruction : line.split("[\\p{Co}▸►]"))
                    if (!instruction.isBlank()) guidance.add(instruction.strip());
            }
            else details.add(line);
        }
        String scope = usefulScope(condition.scope());
        if (scope != null && sharedScopes.contains(compact(scope))) scope = null;
        String text = ParticipationConditionText.displaySentence(condition.text(), condition.kind());
        String label = label(condition);
        String section = switch (condition.kind()) {
            case MANDATORY -> "BASIC";
            case PREFERENCE -> "PREFERENCE";
            case EXCLUSION -> "EXCLUSION";
            case REVIEW_REQUIRED -> "REVIEW";
        };
        var outcomes = new ArrayList<ParticipationOutcome>();
        String audience = null;
        var type = TYPE_RULE.matcher(text);
        // Only explicit subject + application type + permission/prohibition creates an outcome.
        if (condition.kind() != ParticipationKind.REVIEW_REQUIRED && type.matches()) {
            section = "TARGETED";
            label = "신청 유형 제한";
            audience = type.group(1).strip();
            if (audience.matches(".*(?:\\d{4}년|기존|기지원|수혜|참여).*(?:사업|기업).*")) label = "과거 지원 이력";
            String effect = type.group(3).matches("불가|없습니다") ? "INELIGIBLE" : "ELIGIBLE";
            outcomes.add(new ParticipationOutcome(type.group(2).replaceAll("\\h+", ""), effect));
            text = effect.equals("ELIGIBLE") && text.matches(".*(?:에\\h*한해|만)\\h*신청.*")
                    ? EXCLUSIVE_TEXT : "해당 기업에는 아래 신청 유형 기준이 적용됩니다.";
            // The same notice repeats this rule under eligibility and exclusions; compare its fund,
            // not a section introduction such as "any of the following is excluded".
            // Retain every non-shared scope, including fund names and application subtypes.
        } else if (condition.category().equals("지원 대상")
                && condition.scope() != null && condition.scope().contains("주관기관 자격과 별도")) {
            section = "TARGETED";
            audience = "지원받는 창업기업";
            label = "수혜기업 자격";
            scope = "주관기관 신청 자격과 별도";
        }
        if (label.equals("기업 규모") && text.matches("^[｢「『].*중소기업기본법.*제2조\\h*제1항.*제3조에 따른 중소기업이 대상입니다[.]$")) {
            text = "중소기업에 해당해야 합니다.";
        }
        if (label.equals("세금 체납") && compact(text).matches("국세또는지방세체납중인자기업은신청이제한됩니다")) {
            text = "국세 또는 지방세를 체납 중이면 신청이 제한됩니다.";
        }
        details.replaceAll(line -> line.matches("신청 마감일 기준으로 유효한 중소기업 확인서 제출이 가능한 기업[.]?")
                ? "신청 마감일 기준으로 유효한 중소기업확인서를 제출할 수 있어야 합니다." : line);
        details.replaceAll(value -> ParticipationConditionText.displaySentence(value, null));
        exceptions.replaceAll(value -> ParticipationConditionText.displaySentence(value, null));
        text = organizationSummary(condition.text(), details, text);
        if (ParticipationConditionText.incomplete(condition.text())) {
            section = "REVIEW";
            details.add(0, condition.text());
            text = "자격 조건의 문장이 끝까지 확인되지 않아 원문 확인이 필요합니다.";
        }
        return new ParticipationRule(section, label, text, scope, audience, List.copyOf(details),
                List.copyOf(exceptions), List.copyOf(guidance), List.copyOf(outcomes), List.of(condition.source()));
    }


    private static String organizationSummary(String rawTitle, List<String> details, String fallback) {
        var title = Pattern.compile("^(.{2,60})의 신청 기준[.]?$").matcher(rawTitle);
        if (!title.matches()) return fallback;
        var objects = new ArrayList<String>();
        var laws = new ArrayList<String>();
        var legal = Pattern.compile("^(.+?)입니다[.] \\(적용 법령: ([『「｢].+?[』」｣]).*\\)$");
        for (String detail : details) {
            var match = legal.matcher(detail);
            if (match.matches()) {
                if (!objects.contains(match.group(1))) objects.add(match.group(1));
                if (!laws.contains(match.group(2))) laws.add(match.group(2));
            }
        }
        String role = title.group(1).replaceAll("\\h*\\(", "(");
        if (objects.size() == 1 && !laws.isEmpty()) {
            return role + "의 신청 대상은 " + laws.getFirst() + (laws.size() > 1 ? " 등 명시된 법령" : "")
                    + "에 따른 " + objects.getFirst() + "입니다.";
        }
        if (objects.size() > 1) {
            String examples = String.join("·", objects.subList(0, Math.min(2, objects.size())));
            return role + "의 신청 대상은 " + examples + " 등 공고에 명시된 기관 유형입니다.";
        }
        return fallback;
    }

    private static String label(ParticipationCondition condition) {
        String text = condition.text();
        if (text.contains("중소기업기본법") || text.matches("^(?:중소기업|소기업|대기업)(?:이|에).*")) return "기업 규모";
        if (text.contains("사업자등록증") && text.contains("제조")) return "제조업 여부";
        if (text.contains("수출") && !text.contains("가점") && !text.contains("특허")) return "수출 대상";
        if (text.contains("체납")) return "세금 체납";
        if (text.contains("채무불이행")) return "채무불이행";
        if (text.matches(".*휴[·‧ㆍ․]?폐업.*")) return "휴업·폐업";
        if (text.contains("수행대상에서 배제") || text.contains("수행 대상에서 배제")) return "사업 참여 제재";
        if (text.contains("가점")) return "평가 가점";
        if (text.matches(".*(?:성실상환|원금분할상환).*")) return "상환 이력";
        if (text.matches(".*(?:신용도판단정보|일반신용정보|신용평가등급).*")) return "신용 상태";
        if (condition.kind() == ParticipationKind.EXCLUSION && text.matches(".*(?:동일한 자금|동일 자금).*(?:부결|승인).*")) return "재신청 제한";
        if (condition.kind() == ParticipationKind.EXCLUSION && text.matches(".*(?:허위|부정한 방법|자금횡령).*")) return "부정 신청·사용";
        String normalized = compact(text);
        if (normalized.matches(".*(?:참여제한.*제재|제재.*참여제한|수행대상에서배제).*")) return "사업 참여 제재";
        if (normalized.matches(".*(?:환수금|반환금).*(?:반환|미납|종결).*")) return "환수금 반환";
        if (normalized.matches(".*(?:체불사업주|임금체불).*")) return "임금 체불";
        if (normalized.matches(".*(?:사업비계좌|회계처리|사업비집행관리).*")) return "사업비 관리";
        if (text.matches(".*(?:지원기능|지원 기능|지원역량|지원 역량|전문성).*")) return "지원 역량";
        if (text.matches("^(?:공공|민간|주관|참여|공동|수요|운영|전담|영리|비영리)(?:기관|기업).*신청 기준[.]?$")) return "기관 유형";
        if (condition.kind() == ParticipationKind.EXCLUSION) {
            if (condition.category().equals("업종·분야")) return "제외 업종";
            if (condition.category().equals("참여 자격")) return "기타 신청 제한";
        }
        return condition.category();
    }

    private static String usefulScope(String scope) {
        if (scope == null || scope.isBlank()) return null;
        // This is a section heading, not another eligibility requirement.
        if (scope.matches("(?s)^(?:아래|다음)의?\\h*조건\\h*중\\h*하나\\h*이상.*신청\\h*불가[.]?$")) return null;
        var exclusionAudience = Pattern.compile("^(.{1,60}?)(?:이|가)\\h*다음\\h*각\\h*호의?\\h*(?:어느\\h*)?하나에\\h*해당하는\\h*경우\\h*(?:지원사업\\h*)?신청(?:을)?\\h*제한[.]?$").matcher(scope.strip());
        if (exclusionAudience.matches()) return exclusionAudience.group(1).replaceAll("\\h*\\(\\h*또는\\h*", " 또는 ").replace(")", "").strip();
        return scope.strip();
    }

    private static String identity(ParticipationRule row) {
        // Never merge different fund scopes, numerical thresholds, exceptions or logical operators.
        return row.section() + ":" + compact(row.label()) + ":" + compact(row.scope()) + ":"
                + compact(row.audience() == null ? row.text() : row.audience()) + ":"
                + clausesKey(row.details()) + ":" + clausesKey(row.exceptions());
    }

    private static String clausesKey(List<String> values) {
        return String.join("|", values.stream().map(ParticipationGuidePresentation::compact).toList());
    }

    static String compact(String text) {
        if (text == null) return "";
        // Ignore extraction-only bullets/quotes/spacing, while retaining decimals, signs and ranges.
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("(?<!\\d)\\.|\\.(?!\\d)", "")
                .replaceAll("[^\\p{L}\\p{N}.%<>≤≥=+/&|\\-~]", "");
    }

    private static ParticipationRule merge(ParticipationRule a, ParticipationRule b) {
        var sources = new ArrayList<>(a.sources());
        for (var source : b.sources()) {
            if (sources.stream().noneMatch(value -> value.equals(source))) sources.add(source);
        }
        var guidance = new ArrayList<>(a.guidance());
        for (var value : b.guidance()) {
            if (guidance.stream().noneMatch(existing -> compact(existing).equals(compact(value)))) guidance.add(value);
        }
        var outcomes = new ArrayList<>(a.outcomes());
        for (var value : b.outcomes()) if (!outcomes.contains(value)) outcomes.add(value);
        boolean conflict = outcomes.stream().anyMatch(outcome -> outcomes.stream().anyMatch(other ->
                other.variant().equals(outcome.variant()) && !other.status().equals(outcome.status())));
        boolean exclusive = a.text().equals(EXCLUSIVE_TEXT) || b.text().equals(EXCLUSIVE_TEXT);
        if (exclusive && outcomes.stream().filter(value -> value.status().equals("ELIGIBLE"))
                .map(ParticipationOutcome::variant).distinct().count() > 1) conflict = true;
        if (conflict) {
            return new ParticipationRule("REVIEW", a.label(), "원문에서 같은 대상·유형의 신청 기준이 서로 다르게 확인됩니다.",
                    a.scope(), a.audience(), a.details(), a.exceptions(), guidance, outcomes, sources);
        }
        outcomes.sort(Comparator.comparing(ParticipationOutcome::variant));
        return new ParticipationRule(a.section(), a.label(), exclusive ? EXCLUSIVE_TEXT : a.text(), a.scope(), a.audience(), a.details(),
                a.exceptions(), guidance, outcomes, sources);
    }

    static List<ParticipationVariant> variants(List<String> texts) {
        var result = new LinkedHashMap<String, ParticipationVariant>();
        var pattern = Pattern.compile("\\((유형\\h*[0-9A-Za-zⅠ-Ⅹ]+)\\)\\h*([^\\r\\n]{2,100}?)(?=,?\\h*\\(유형|\\r?\\n|$)");
        for (String text : texts) {
            if (text == null) continue;
            var matches = pattern.matcher(text.substring(0, Math.min(12000, text.length())));
            while (matches.find()) {
                String name = matches.group(1).replaceAll("\\h+", "");
                String description = matches.group(2).strip().replaceFirst("[,，]$", "").strip();
                // A short explicit definition only; never use a condition, cost or partial nested cell.
                if (description.length() > 55 || description.matches(".*(?:신청|기업당|최대|백만원|지원금|[()\\t]).*")) continue;
                result.putIfAbsent(name, new ParticipationVariant(name, description));
            }
        }
        return result.size() > 1 ? List.copyOf(result.values()) : List.of();
    }

    private static int order(String section) {
        return switch (section) {
            case "BASIC" -> 0;
            case "TARGETED" -> 1;
            case "EXCLUSION" -> 2;
            case "PREFERENCE" -> 3;
            default -> 4;
        };
    }
}
