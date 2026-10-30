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
            if (ParticipationAudience.recipient(List.of(source), input.scopes().displayContext(List.of(source)))) continue;
            String raw = source.excerpt();
            addResearchCondition(source, input, result);
            addStructuredConditions(source, input, result);
            String[] lines = raw.split("\\R", -1);
            int[] lineStarts = new int[lines.length];
            var breaks = Pattern.compile("\\R").matcher(raw);
            for (int index = 1; index < lines.length && breaks.find(); index++) lineStarts[index] = breaks.end();
            boolean qualification = false;
            boolean inTable = false;
            String rowScope = null;
            for (int i = 0; i < lines.length && result.size() < 48; i++) {
                String line = lines[i].strip();
                if (line.equals("[표]")) { inTable = true; continue; }
                if (line.equals("[/표]")) { inTable = false; continue; }
                // Column headers and cells are handled separately; never turn a whole target row into prose.
                if (inTable) continue;
                if (line.matches(".*(?:과제별\\s*신청자격|선정\\s*대상).*")) qualification = true;
                if (qualification && line.matches("[가-힣A-Za-z0-9 ]{1,20}과제") && !line.contains("신청")) rowScope = line;
                if (qualification && line.equals("과제") && i > 0 && lines[i - 1].strip().matches("[가-힣A-Za-z]{1,10}"))
                    rowScope = lines[i - 1].strip() + "과제";
                var label = LABEL.matcher(line);
                boolean labelled = label.find();
                var company = Pattern.compile("^[(（]기\\s*업[)）]\\s*").matcher(line);
                boolean companyRow = qualification && company.find();
                if (!labelled && !companyRow) continue;
                if (labelled && line.substring(label.end()).strip().matches("^(?:및|과|와)\\s*(?:방법|절차|선정절차|지원내용).*$")) {
                    qualification = true;
                    continue;
                }
                if (labelled && line.startsWith("ㅇ") && !qualification) continue;
                qualification = true;
                String quote = line.substring(labelled ? label.end() : company.end()).strip();
                int last = i;
                if (quote.isEmpty()) {
                    last++;
                    while (last < lines.length && lines[last].isBlank()) last++;
                    if (last >= lines.length) continue;
                    quote = lines[last].strip();
                    if (ParticipationConditionStructure.roleLine(quote)) continue;
                }
                // Read wrapped prose, never a following row, note, or a whole table.
                while (last + 1 < lines.length && quote.length() < 260) {
                    String next = lines[last + 1].strip();
                    if (next.isEmpty() || STOP.matcher(next).matches()
                            || next.matches("^(?:[-◦○●]|\\d+[.)]).*")
                            || next.matches("\\d{1,2}(?:-\\d{1,2})*[.)]?|[가-힣A-Za-z0-9 ]{1,20}과제")
                            || LABEL.matcher(next).find() || (next.contains("\t") && !next.startsWith("("))) break;
                    quote += "\n" + lines[++last].strip();
                }
                String exact = exactQuote(raw.substring(lineStarts[i]), quote);
                if (exact == null) continue;
                int exactStart = raw.indexOf(exact, lineStarts[i]);
                String value = clean(exact);
                if (ParticipationConditionStructure.multipleTargets(exact)) continue;
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
                String sentence = value + particle(value) + " 지원 대상입니다.";
                var details = new ArrayList<String>();
                boolean unsupportedNote = false;
                int noteStart = last + 1;
                while (noteStart < lines.length && lines[noteStart].isBlank()) noteStart++;
                while (noteStart < lines.length && lines[noteStart].strip().matches("^(?:[※*]|단[,，]|다만).*")) {
                    int noteEnd = noteStart;
                    while (noteEnd + 1 < lines.length && !lines[noteEnd + 1].isBlank()
                            && !STOP.matcher(lines[noteEnd + 1].strip()).matches()
                            && !lines[noteEnd + 1].strip().matches("^[◦○●].*")) noteEnd++;
                    String note = clean(String.join(" ", Arrays.copyOfRange(lines, noteStart, noteEnd + 1)));
                    String extra = note.replaceFirst("^(?:단|다만)[,，]\\s*", "");
                    if (!ParticipationDisplayQuality.readable(extra)) { unsupportedNote = true; break; }
                    String exceptionPrefix = sentence.equals(value + particle(value) + " 지원 대상입니다.")
                            ? value + "이며, 다만 " : sentence + " 또한, ";
                    if (extra.endsWith("도 지원 가능"))
                        sentence = exceptionPrefix + extra.replaceFirst("지원 가능$", "지원할 수 있습니다.");
                    else if (extra.matches(".*신청\\s*대상에\\s*포함$"))
                        sentence = exceptionPrefix + extra + "됩니다.";
                    else if (note.matches("^(?:단|다만)[,，].*")
                            || Pattern.compile("제외|예외|필수|불가|제한|하여야|해야").matcher(note).find()) {
                        unsupportedNote = true; break;
                    } else details.add(note);
                    exact = raw.substring(exactStart, lineStarts[noteEnd] + lines[noteEnd].length());
                    last = noteEnd;
                    noteStart = last + 1;
                    while (noteStart < lines.length && lines[noteStart].isBlank()) noteStart++;
                }
                // An explicit numbered requirement list is additional eligibility, not a wrapped applicant noun.
                if (noteStart < lines.length && lines[noteStart].strip().matches("^-\\s*신청\\s*조건.*(?:모두|충족).*")) {
                    int listEnd = noteStart;
                    while (listEnd + 1 < lines.length) {
                        String next = lines[listEnd + 1].strip();
                        if (next.matches("^(?:[<□■]|[가-하][.)]|\\d+[.)]).*")) break;
                        listEnd++;
                    }
                    String additional = String.join("\n", Arrays.copyOfRange(lines, noteStart, listEnd + 1)).strip();
                    // These are repeated row labels under the explicit '신청조건' heading, not applicant text.
                    additional = additional.replaceAll("(?m)^\\h*\\[/?표\\]\\h*$", "")
                            .replaceAll("(?m)^\\h*신청(?:\\h|⏎)*조건\\h*\\t", "")
                            .replaceAll("(?m)^\\h*(?:신청|조건)\\h*$", "")
                            .replaceAll("(?m)^\\h*신청\\h+(?=영위)", "")
                            .replaceFirst("^-\\s*", "").replaceAll("\\s+", " ").strip();
                    if (additional.length() > 500 || !additional.contains("①") || !additional.contains("②")
                            || !ParticipationDisplayQuality.readable(additional)) continue;
                    details.add(additional);
                    exact = raw.substring(exactStart, lineStarts[listEnd] + lines[listEnd].length()).stripTrailing();
                    last = listEnd;
                }
                if (unsupportedNote || sentence.length() > 320 || exact.length() > 600
                        || !ParticipationDisplayQuality.sentence(sentence)
                        || !ParticipationDisplayQuality.completeQuote(raw, exact)) continue;
                String code = primaryCriterion(value);
                if (code == null) continue;
                String scope = cleanScope(rowScope != null ? rowScope : input.scopes().displayScope(List.of(source), exact));
                String key = code + ":" + Objects.toString(scope, "") + ":" + clean(exact).replaceAll("\\s+", "");
                var previous = result.get(key);
                var sources = new ArrayList<RequirementSource>();
                if (previous != null) sources.addAll(previous.rule().sources());
                if (!sources.contains(source)) sources.add(source);
                var rule = new ParticipationRule("BASIC", ParticipationFocusCriterion.valueOf(code).label, sentence, scope, null,
                        List.copyOf(details), List.of(), List.of(), List.of(), sources);
                result.put(key, new Condition(code, rule, exact));
                i = last;
            }
        }
        return List.copyOf(result.values());
    }


    private static void addStructuredConditions(RequirementSource source, ParticipationRewriteProjection.Input input,
            Map<String, Condition> result) {
        for (var target : ParticipationConditionStructure.tableTargets(source.excerpt())) {
            String value = clean(target.quote().replace("⏎", " "));
            String parent = cleanScope(input.scopes().displayScope(List.of(source), target.quote()));
            String scope = parent == null ? target.title() : parent + " · " + target.title();
            addStructured(source, result, value + particle(value) + " 지원 대상입니다.",
                    target.quote(), scope, null, primaryCriterion(value));
        }
        for (var target : ParticipationConditionStructure.roleTargets(source.excerpt())) {
            String value = target.value();
            String sentence = target.audience() + "은 " + value + (particle(value).equals("가") ? "여야 합니다." : "이어야 합니다.");
            String scope = cleanScope(input.scopes().displayScope(List.of(source), target.quote()));
            addStructured(source, result, sentence, target.quote(), scope, target.audience(), primaryCriterion(value));
        }
    }

    private static void addStructured(RequirementSource source, Map<String, Condition> result,
            String sentence, String quote, String scope, String audience, String code) {
        if (code == null || sentence.length() > 320 || result.size() >= 48 || !ParticipationDisplayQuality.sentence(sentence)) return;
        String key = code + ":" + Objects.toString(scope, "") + ":" + Objects.toString(audience, "")
                + ":" + clean(quote).replaceAll("\\s+", "");
        var previous = result.get(key);
        var sources = new ArrayList<RequirementSource>();
        if (previous != null) sources.addAll(previous.rule().sources());
        if (!sources.contains(source)) sources.add(source);
        var rule = new ParticipationRule("BASIC", ParticipationFocusCriterion.valueOf(code).label, sentence, scope, audience,
                List.of(), List.of(), List.of(), List.of(), sources);
        result.put(key, new Condition(code, rule, quote));
    }

    private static String primaryCriterion(String value) {
        var fields = ParticipationFocusCriterion.matching(value, "");
        if (fields.contains("REVENUE")) return "REVENUE";
        if (fields.contains("BUSINESS_AGE")) return "BUSINESS_AGE";
        // A parenthetical worker-count definition describes the applicant's enterprise size.
        if (value.matches(".*(?:기업|소공인|소상공인)\\s*\\([^()]*상시\\s*근로자[^()]*\\)$"))
            return "BUSINESS_FORM";
        if (Pattern.compile("업종|업태|KSIC|한국표준산업분류|(?:제조업|서비스업|정보통신업).*영위").matcher(value).find())
            return "SECTOR";
        // Explicit target clauses identify an applicant even when '중소·중견 제조기업' has no contiguous '중소기업'.
        if (ParticipationConditionStructure.entity(value)) return "BUSINESS_FORM";
        return fields.contains("SECTOR") ? "SECTOR" : null;
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
            if (!ParticipationDisplayQuality.sentence(sentence)) continue;
            String scope = cleanScope(input.scopes().displayScope(List.of(source), quote));
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
