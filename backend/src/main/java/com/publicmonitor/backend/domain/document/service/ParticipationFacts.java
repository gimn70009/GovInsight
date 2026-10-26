package com.publicmonitor.backend.domain.document.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Same bounded fact contract as participation/facts.py; no model calls or source mutation. */
final class ParticipationFacts {
    private static final Pattern LAW = pattern("[「『｢]([^」』｣\\n]{2,100}(?:법|법률))[」』｣](시행령|시행규칙)?((?:제\\d+조(?:의\\d+)?)(?:제?\\d+(?:항|호)(?:의\\d+)?)*)?");
    private static final Pattern ARTICLE = pattern("제\\d+조(?:의\\d+)?(?:제?\\d+(?:항|호)(?:의\\d+)?)*");
    private static final Pattern ROLE = pattern("주관기관|공동기관|수혜기업|운영기관|수행기관|[가-힣A-Za-z]{2,12}과제");
    private static final Pattern ENTITY = pattern("기업부설연구소|연구개발전담부서|비영리법인|법인사업자|개인사업자|중소기업|중견기업|대기업|창업기업|소상공인|소공인|공공기관|민간기관|전문기관|상권활성화구역|골목형상점가|전통시장|상점가|상인연합회|상인회|협동조합|대학교|대학|연구기관|사업자단체|단체|제조업|서비스업|건설업|한국산업기술진흥협회|글로벌강소기업");
    private static final Pattern METRIC = pattern("직접수출액?|수출액?|수출실적|매출액?|부채비율|유동비율|상인회비납부율|상인회가입률|가맹률|가입률|계약실적|납품실적|상시근로자수|근로자수|업력|창업|설립|영업점포");
    private static final Pattern NUMBER = pattern("(?<prefix>[A-Z]?)(?<value>\\d+(?:,\\d{3})*(?:\\.\\d+)?)(?<unit>억원|만원|천원|원|만불|불|달러|년|개월|%|점|건|명|개사|개|종)?");
    private static final Pattern CRITICAL = pattern("불인정|허용금지|없음|않|제외|불가|예외|단서|또는|모두|하나|무관|한정|기업조건|등록|출원|실시권|주된|공고일|접수일|마감일|개최일|직전년도|최근|전일|이전|이후|>=|<=|≥|≤|>|<|이상|이하|초과|미만");
    private static final Pattern ANCHOR = pattern("기업부설연구소|연구개발전담부서|비영리법인|법인사업자|개인사업자|중소기업|중견기업|대기업|창업기업|소상공인|소공인|공공기관|민간기관|전문기관|상권활성화구역|골목형상점가|전통시장|상점가|상인연합회|상인회|협동조합|대학교|대학|연구기관|사업자단체|단체|제조업|서비스업|건설업|한국산업기술진흥협회|글로벌강소기업|특허|인증|신용등급|신용평가등급");
    private static final Pattern GRADE = pattern("[A-D]{1,3}[+-]?(?![A-Za-z])");
    private static final List<String[]> ALIASES = List.of(
            new String[]{"주관연구개발기관|주관연구기관", "주관기관"},
            new String[]{"공동연구개발기관|공동연구기관", "공동기관"},
            new String[]{"중소기업자", "중소기업"},
            new String[]{"중소/중견기업", "중소기업또는중견기업"},
            new String[]{"기업인경우는?|기업일경우|기업이면", "기업조건"},
            new String[]{"인정(?:되지는|되지|하지는|하지)않(?:습니다|는다|는|음)?|인정할수없(?:습니다|는|음)?|미인정|인정불가", "불인정"},
            new String[]{"불인정대상", "불인정"},
            new String[]{"허용되지않(?:습니다|는다|는|음)?|불허", "허용금지"},
            new String[]{"없어야|없는|없어", "없음"},
            new String[]{"혹은|거나", "또는"},
            new String[]{"전부충족|동시에충족", "모두충족"},
            new String[]{"(?<!직)전년도", "직전년도"},
            new String[]{"접수마감일(?:현재|기준)", "접수마감일"},
            new String[]{"이내", "이하"});
    private static final Map<String, String> COMPARE = Map.ofEntries(Map.entry("이상", "GE"), Map.entry("≥", "GE"), Map.entry(">=", "GE"), Map.entry("이하", "LE"), Map.entry("≤", "LE"), Map.entry("<=", "LE"), Map.entry("초과", "GT"), Map.entry(">", "GT"), Map.entry("미만", "LT"), Map.entry("<", "LT"));
    private static final Set<String> NEGATIVE = Set.of("불인정", "허용금지", "없음", "않", "제외", "불가");
    private record Reference(String name, String provision) {}
    private record Quantities(Map<List<String>, Integer> known, List<List<String>> unknown) {}

    private static Pattern pattern(String value) { return Pattern.compile(value, Pattern.UNICODE_CHARACTER_CLASS); }
    private static String compact(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("(^|[\\s.※*;(])(?:단|다만)(?:[,，]\\s*|\\s+)", "$1단서")
                .replaceAll("\\s+", "");
    }
    private static String normalize(String value) {
        value = compact(value);
        for (var alias : ALIASES) value = pattern(alias[0]).matcher(value).replaceAll(alias[1]);
        return value;
    }

    private static String[] legalContent(String text, String quote) {
        text = compact(text); quote = compact(quote);
        var refs = LAW.matcher(quote).results().map(m -> new Reference(
                m.group(1) + Objects.toString(m.group(2), ""), Objects.toString(m.group(3), ""))).toList();
        var names = new HashSet<String>();
        refs.forEach(ref -> names.add(ref.name()));
        for (var match : LAW.matcher(text).results().toList())
            if (!names.contains(match.group(1) + Objects.toString(match.group(2), ""))) return null;
        for (var ref : refs) {
            text = text.replaceAll("[「」『』｢｣]", "").replace(ref.name() + ref.provision(), "");
            if (!ref.provision().isEmpty())
                text = text.replaceAll(Pattern.quote(ref.name()) + "(?!제\\d)", "");
        }
        quote = LAW.matcher(quote).replaceAll("");
        var lawNames = pattern("[가-힣]{2,}법(?:시행령|시행규칙)?(?=제\\d|에|$|[.,()])");
        if (!matches(lawNames, quote).containsAll(matches(lawNames, text))
                || !matches(ARTICLE, quote).containsAll(matches(ARTICLE, text))) return null;
        return new String[]{normalize(text), normalize(quote)};
    }

    private static List<String> matches(Pattern pattern, String value) {
        return pattern.matcher(value).results().map(m -> m.group()).toList();
    }
    private static String previous(Pattern pattern, String value, int position) {
        var found = matches(pattern, value.substring(0, position));
        return found.isEmpty() ? "" : found.getLast();
    }
    private static String metric(String value) {
        return switch (value) {
            case "매출액" -> "매출";
            case "직접수출액" -> "직접수출";
            case "수출액", "수출실적" -> "수출";
            case "설립", "창업" -> "업력";
            default -> value;
        };
    }
    private static Quantities quantities(String value) {
        var known = new LinkedHashMap<List<String>, Integer>();
        var unknown = new ArrayList<List<String>>();
        var numbers = NUMBER.matcher(value);
        while (numbers.find()) {
            String metric = metric(previous(METRIC, value, numbers.start()));
            String role = previous(ROLE, value, numbers.start());
            var after = pattern("^(?:은|는|이|가)?(이상|이하|초과|미만|>=|<=|≥|≤|>|<)").matcher(value.substring(numbers.end()));
            var before = pattern("(>=|<=|≥|≤|>|<)$").matcher(value.substring(0, numbers.start()));
            String operator = after.find() ? COMPARE.get(after.group(1)) : before.find() ? COMPARE.get(before.group(1)) : "";
            String amount = new BigDecimal(numbers.group("value").replace(",", "")).stripTrailingZeros().toPlainString();
            var item = List.of(role, metric, numbers.group("prefix"), amount,
                    Objects.toString(numbers.group("unit"), ""), operator);
            if (metric.isEmpty()) unknown.add(item); else known.merge(item, 1, Integer::sum);
        }
        return new Quantities(known, unknown);
    }
    private static Set<List<String>> entities(String value) {
        var result = new HashSet<List<String>>();
        ENTITY.matcher(value).results().forEach(m -> result.add(List.of(previous(ROLE, value, m.start()), m.group())));
        return result;
    }
    private static Map<List<String>, Integer> qualifiers(String value) {
        var result = new HashMap<List<String>, Integer>();
        CRITICAL.matcher(value).results().forEach(m -> {
            String term = m.group();
            String anchor = NEGATIVE.contains(term) ? previous(ANCHOR, value, m.start()) : "";
            result.merge(List.of(previous(ROLE, value, m.start()), anchor, COMPARE.getOrDefault(term, term)), 1, Integer::sum);
        });
        return result;
    }

    private record Branch(Quantities quantities, Set<List<String>> entities,
                          Map<List<String>, Integer> qualifiers, List<String> grades) {}
    private static Map<Branch, Integer> alternatives(String value) {
        if (!value.contains("또는")) return Map.of();
        var result = new HashMap<Branch, Integer>();
        for (String branch : value.split("또는", -1))
            result.merge(new Branch(quantities(branch), entities(branch), qualifiers(branch),
                    matches(GRADE, branch)), 1, Integer::sum);
        return result;
    }
    static boolean equivalent(String text, String quote) {
        var pair = legalContent(text, quote);
        if (pair == null) return false;
        text = pair[0]; quote = pair[1];
        return quantities(text).equals(quantities(quote))
                && alternatives(text).equals(alternatives(quote))
                && entities(text).equals(entities(quote))
                && new HashSet<>(matches(ROLE, text)).equals(new HashSet<>(matches(ROLE, quote)))
                && qualifiers(text).equals(qualifiers(quote))
                && matches(GRADE, text).equals(matches(GRADE, quote))
                && text.replaceAll(">=|<=", "").contains("=") == quote.replaceAll(">=|<=", "").contains("=")
                && text.contains("±") == quote.contains("±") && text.contains("×") == quote.contains("×");
    }
}
