package com.publicmonitor.backend.domain.document.service;

import java.util.*;
import java.util.regex.Pattern;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;

/** Exact labelled clauses only. No model request or invented value, role, or program. */
final class ParticipationLocalConditions {
    record Condition(String code, ParticipationRule rule, String quote) {}
    private static final Pattern LABEL = Pattern.compile("^(?:[□■◦○ㅇ]|[가-하][.)])?\\s*[(（]?(?:(?:신청|지원|참여)\\s*(?:대상|자격)|대상)[)）]?\\s*[:：]?\\s*");
    private static final Pattern ENTITY = Pattern.compile(".*(?:기업|기업자|기관|단체|소공인|소상공인|상점가|시장|상권활성화구역)(?:\\s*\\([^()]*\\))?$");
    private static final Pattern STOP = Pattern.compile("^(?:[□■※*◆]|\\[/?표\\]|[가-하][.)]|[-◦○ㅇ]\\s*[(（]|[(（]\\s*(?:기\\s*업|제\\s*품)[)）]).*");

    static List<Condition> extract(ParticipationRewriteProjection.Input input) {
        if (input.scopes() == null) return List.of();
        var result = new LinkedHashMap<String, Condition>();
        for (var evidence : input.evidence().values()) for (var source : evidence) {
            if (ParticipationAudience.recipient(List.of(source), input.scopes().context(List.of(source)))) continue;
            String raw = source.excerpt();
            addResearchCondition(source, input, result);
            String[] lines = raw.split("\\R", -1);
            int[] lineStarts = new int[lines.length];
            var breaks = Pattern.compile("\\R").matcher(raw);
            for (int index = 1; index < lines.length && breaks.find(); index++) lineStarts[index] = breaks.end();
            boolean qualification = false;
            String rowScope = null;
            for (int i = 0; i < lines.length && result.size() < 48; i++) {
                String line = lines[i].strip();
                if (line.matches(".*(?:과제별\\s*신청자격|선정\\s*대상).*")) qualification = true;
                if (qualification && line.matches("[가-힣A-Za-z0-9 ]{1,20}과제") && !line.contains("신청")) rowScope = line;
                if (qualification && line.equals("과제") && i > 0 && lines[i - 1].strip().matches("[가-힣A-Za-z]{1,10}"))
                    rowScope = lines[i - 1].strip() + "과제";
                var label = LABEL.matcher(line);
                boolean labelled = label.find();
                var company = Pattern.compile("^[(（]기\\s*업[)）]\\s*").matcher(line);
                boolean companyRow = qualification && company.find();
                if (!labelled && !companyRow) continue;
                if (labelled && line.startsWith("ㅇ") && !qualification) continue;
                qualification = true;
                String quote = line.substring(labelled ? label.end() : company.end()).strip();
                int last = i;
                if (quote.isEmpty()) {
                    last++;
                    if (last >= lines.length) continue;
                    quote = lines[last].strip();
                }
                // Read wrapped prose, never a following row, note, or a whole table.
                while (last + 1 < lines.length && (!entity(clean(quote)) || lines[last + 1].strip().matches("^\\(상시.*")) && quote.length() < 260) {
                    String next = lines[last + 1].strip();
                    if (next.isEmpty() || STOP.matcher(next).matches() || (next.contains("\t") && !next.startsWith("("))) break;
                    quote += "\n" + lines[++last].strip();
                }
                String exact = exactQuote(raw.substring(lineStarts[i]), quote);
                if (exact == null) continue;
                int exactStart = raw.indexOf(exact, lineStarts[i]);
                String value = clean(exact);
                if (companyRow && value.contains("아래 요건 중 어느 하나")) {
                    int end = raw.indexOf("[/표]", exactStart + exact.length());
                    if (end < 0 || end - exactStart > 550) continue;
                    String table = raw.substring(exactStart, end + "[/표]".length());
                    var alternatives = Pattern.compile("(?m)^[①-⑳]\\s+([^\\r\\n]+)").matcher(table).results()
                            .map(m -> clean(m.group(1))).toList();
                    if (alternatives.size() < 2 || alternatives.stream().anyMatch(a -> !entity(a))) continue;
                    value = value.substring(0, value.indexOf("아래 요건")).replaceFirst("자로\\s*$", "자 중 ")
                            + String.join(" 또는 ", alternatives);
                    exact = table;
                }
                if (Pattern.compile("[가-힣0-9]{35,}").matcher(value).find()) continue;
                if (!entity(value) || value.length() > 240 || value.matches(".*(?:아래|다음|상기).*(?:조건|요건|기준|해당).*")) continue;
                if (value.matches(".*(?:서류|제출|신청방법|신청기간|사업목적|사업비|선정된|선정 후|지원금|https?://|[□■]).*")) continue;
                // Do not erase an adjacent exception that changes the headline eligibility.
                String exception = last + 1 < lines.length ? lines[last + 1].strip() : "";
                String sentence = value + particle(value) + " 지원 대상입니다.";
                if (exception.matches("^(?:[*※]\\s*)?(?:단|다만)[,，].*")) {
                    String extra = clean(exception).replaceFirst("^(?:단|다만)[,，]\\s*", "");
                    if (!extra.endsWith("도 지원 가능")) continue;
                    sentence = value + "이며, 다만 " + extra.replaceFirst("지원 가능$", "지원할 수 있습니다.");
                    int end = raw.indexOf(lines[last + 1], exactStart + exact.length());
                    if (end < 0) continue;
                    exact = raw.substring(exactStart, end + lines[last + 1].length());
                }
                if (sentence.length() > 320 || exact.length() > 600) continue;
                var fields = ParticipationFocusCriterion.matching(value, "");
                String code = fields.contains("REVENUE") ? "REVENUE" : fields.contains("BUSINESS_AGE") ? "BUSINESS_AGE"
                        : fields.contains("BUSINESS_FORM") ? "BUSINESS_FORM" : fields.contains("SECTOR") ? "SECTOR" : null;
                if (code == null) continue;
                String scope = cleanScope(rowScope != null ? rowScope : input.scopes().scope(List.of(source), exact));
                String key = code + ":" + Objects.toString(scope, "") + ":" + clean(exact).replaceAll("\\s+", "");
                var previous = result.get(key);
                var sources = new ArrayList<RequirementSource>();
                if (previous != null) sources.addAll(previous.rule().sources());
                if (!sources.contains(source)) sources.add(source);
                var rule = new ParticipationRule("BASIC", ParticipationFocusCriterion.valueOf(code).label, sentence, scope, null,
                        List.of(), List.of(), List.of(), List.of(), sources);
                result.put(key, new Condition(code, rule, exact));
                i = last;
            }
        }
        return List.copyOf(result.values());
    }

    private static void addResearchCondition(RequirementSource source, ParticipationRewriteProjection.Input input,
            Map<String, Condition> result) {
        if (!Objects.toString(source.sectionTitle(), "").replaceAll("\\s+", "").contains("신청자격")) return;
        // Transform only a complete, explicit obligation, keeping the applicant role and the exception.
        var clauses = Pattern.compile("(?m)^[◦○ㅇ-]?\\h*((?:주관|공동)연구개발기관이\\h*기업인\\h*경우는[^\\r\\n]{1,280})$").matcher(source.excerpt());
        while (clauses.find() && result.size() < 48) {
            String quote = clauses.group(1).strip();
            String value = clean(quote);
            var condition = Pattern.compile("((?:주관|공동)연구개발기관이 기업인 경우)는 (.+법인사업자)이어야 하며, (.+기업부설연구소\\([^()]+\\)를) 보유하고 있어야 함\\(연구개발전담부서는 불인정\\)").matcher(value);
            if (!condition.matches()) continue;
            String sentence = condition.group(1) + ", " + condition.group(2) + "여야 하며, "
                    + condition.group(3) + " 보유해야 합니다. 연구개발전담부서는 인정되지 않습니다.";
            String scope = cleanScope(input.scopes().scope(List.of(source), quote));
            String key = "BUSINESS_FORM:" + Objects.toString(scope, "") + ":" + value.replaceAll("\\s+", "");
            var previous = result.get(key);
            var sources = new ArrayList<RequirementSource>();
            if (previous != null) sources.addAll(previous.rule().sources());
            if (!sources.contains(source)) sources.add(source);
            var rule = new ParticipationRule("BASIC", ParticipationFocusCriterion.BUSINESS_FORM.label, sentence, scope, null,
                    List.of(), List.of(), List.of(), List.of(), sources);
            result.put(key, new Condition("BUSINESS_FORM", rule, quote));
        }
    }

    static boolean covers(Condition local, List<RequirementSource> sources, String quote) {
        return local.rule().sources().stream().anyMatch(sources::contains)
                && clean(LABEL.matcher(quote).replaceFirst("")).replaceAll("\\s+", "").equals(clean(local.quote()).replaceAll("\\s+", ""));
    }

    private static boolean entity(String text) { return ENTITY.matcher(text).matches(); }

    private static String exactQuote(String raw, String quote) {
        String target = quote.replaceAll("\\s+", "");
        var chars = new StringBuilder(); var offsets = new ArrayList<Integer>();
        for (int i = 0; i < raw.length(); i++) if (!Character.isWhitespace(raw.charAt(i))) { chars.append(raw.charAt(i)); offsets.add(i); }
        int from = chars.indexOf(target);
        return from < 0 || target.isEmpty() ? null : raw.substring(offsets.get(from), offsets.get(from + target.length() - 1) + 1);
    }

    static String cleanScope(String value) {
        if (value == null) return null;
        String result = Arrays.stream(value.split(" / ")).map(part -> Arrays.stream(part.split(" · "))
                .filter(label -> !label.strip().matches("^(?:[（(□■◦○ㅇ*※-]|20[0-9]{2}년).*"))
                .filter(label -> label.strip().length() <= 60)
                .collect(java.util.stream.Collectors.joining(" · "))).filter(part -> !part.isBlank())
                .distinct().collect(java.util.stream.Collectors.joining(" / "));
        return result.isBlank() ? null : result;
    }

    static String clean(String value) {
        return value.replaceFirst("^[□■◦○ㅇ]\\s*", "")
                .replaceFirst("^[(（](?:신청자격|지원대상|신청대상|대상|기\\s*업)[)）]\\s*", "")
                .replaceAll("[｢｣「」『』]", "").replaceAll("법(?=제[0-9]|시행령)", "법 ").replaceFirst("^[*※]+\\s*", "")
                .replaceAll("(달성|충족|대상|기준|기업|이상|이하|초과|미만|보유|해당|가능)[*＊]{1,2}(?=\\s|$|[.,]|의(?:\\s|$))", "$1").replaceAll("\\s+", " ").strip();
    }

    private static String particle(String value) {
        char last = value.charAt(value.length() - 1);
        return last >= '가' && last <= '힣' && (last - '가') % 28 == 0 ? "가" : "이";
    }
}
