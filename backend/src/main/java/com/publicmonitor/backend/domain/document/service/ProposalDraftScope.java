package com.publicmonitor.backend.domain.document.service;

import java.util.regex.Pattern;

/** Conservative exclusion; ambiguous documents still receive AI template classification. */
public final class ProposalDraftScope {
    private ProposalDraftScope() {}

    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    }
    private static final Pattern ADMIN = pattern(
            "(?:확인서|동의서|확약서|서약서|증명서|자가\\s*진단(?:표)?|체크리스트)\\s*$|"
            + "\\b(?:consent form|declaration|certificate|checklist)\\s*$");
    private static final Pattern NOTICE = pattern(
            "(?:공고|공고문)\\s*$|공고\\s*제\\s*\\d|\\b(?:call for applications|funding announcement)\\s*$");
    private static final Pattern FORM = pattern(
            "(?:사업\\s*계획서|연구\\s*개발\\s*계획서|제안서)(?:\\s*[\\[(].*[\\])])?\\s*$|"
            + "\\b(?:business plan|research proposal|executive summary of application)\\s*$");
    private static final Pattern TOPIC = pattern("사업|제품|서비스|협력|연구|개발|추진|목표|계획|성과|역량|현황|활용|필요성|시장");
    private static final Pattern WRITE = pattern(
            "(?:작성|기재|서술|기술|설명)\\s*(?:해\\s*주|해주|하십|하세|합|할\\s*것|바람|요망)|"
            + "(?:등|내용|계획|현황|상세히|구체적으로|이내로|이내)\\s*(?:작성|기재|서술)\\s*[)）]?$");
    private static final Pattern PLAN_FIELD = pattern(
            "(?:사업\\s*목표|사업\\s*내용|추진\\s*계획|기대\\s*효과|연구\\s*목표|project objectives|implementation plan)");
    private static final Pattern ADMIN_PROMPT = pattern("해당\\s*여부|동의|날인|서명|체납|확인서|증빙|신청\\s*자격|제출\\s*(?:목록|서류)");
    private static final Pattern ENGLISH_PROMPT = pattern(
            "\\b(?:describe|explain)\\b.+\\b(?:business|project|research|cooperation|products?)\\b");

    public static String exclusion(String text) {
        var lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        // A real narrative form can follow an administrative cover or notice in the same file.
        boolean narrative = lines.stream().filter(line -> line.length() <= 320).anyMatch(line ->
                !ADMIN_PROMPT.matcher(line).find()
                && ((TOPIC.matcher(line).find() && WRITE.matcher(line).find())
                    || ENGLISH_PROMPT.matcher(line).find()));
        boolean form = false;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).length() > 120 || !FORM.matcher(lines.get(index)).find()) continue;
            for (int next = index + 1; next < Math.min(index + 5, lines.size()); next++) {
                if (PLAN_FIELD.matcher(lines.get(next).replaceAll("^[ #□ㅇ0-9.()\\-]+|[ #□ㅇ0-9.()\\-]+$", "")).matches()) form = true;
            }
        }
        if (narrative || form) return "";
        var headers = lines.stream().limit(24).filter(line -> line.length() <= 160).toList();
        if (headers.stream().anyMatch(line -> NOTICE.matcher(line).find())) {
            return "공고·안내 문서는 초안 작성 대상이 아닙니다. 신청서나 사업계획서 양식을 선택해 주세요.";
        }
        if (headers.stream().anyMatch(line -> ADMIN.matcher(line).find())) {
            return "확인서·동의서·체크리스트는 초안 작성 대상이 아닙니다. 해당 여부 확인과 서명은 직접 작성해 주세요.";
        }
        return "";
    }
}
