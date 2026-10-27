package com.publicmonitor.backend.domain.document.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Recognize institution names before interpreting list separators or trailing descriptions. */
final class ResponsibleOrganizationNames {
    private static final String TYPES = "산학협력단|상공회의소|테크노파크|공단|재단|협회|진흥원|진흥회|연구원|연구소|센터|대학교|대학|공사|"
            + "기술원|개발원|평가원|정보원|의료원|교육원|수련원|연수원|유통원|관리원|보호원|표준원|시험원|인증원|자료원|문화원|보건원|수목원|과학원|기록원|감사원|감독원|소비자원|부동산원|박물관|미술관|도서관|지원단|사업단|사무국|조합|위원회|중앙회|연구회|연합회|은행";
    private static final String GOVERNMENT = "(?:(?:과학기술정보통신|산업통상자원|산업통상|중소벤처기업|기획재정|재정경제|"
            + "국토교통|보건복지|고용노동|행정안전|문화체육관광|농림축산식품|해양수산|기후에너지환경|"
            + "교육|환경|법무|국방|외교|통일|여성가족|성평등가족|중기|산업)부|"
            + "[가-힣]{0,16}(?:조달|농촌진흥|국가유산|특허|관세|국세|기상|통계|경찰|소방|검찰|병무|산림|질병관리|중소벤처기업|중소기업)청)";
    private static final String REGION = "(?:[가-힣]{2,16}(?:특별자치도|특별자치시|특별시|광역시|시청|도청|군청|구청)|"
            + "(?:경기|강원|충청남|충청북|전라남|전라북|경상남|경상북|제주)도|"
            + "(?:서울|부산|대구|인천|광주|대전|울산|세종)시|[가-힣]{2,10}구)";
    private static final String END = "(?=$|[\\h,;/|()·ㆍ‧∙&.]|(?:과|와|은|는|이|가|에서|로|으로)(?=\\h)|입니다|이다)";
    private static final Pattern NAME = Pattern.compile("^(?:(?:\\([재사]\\)\\h*|(?:재단법인|사단법인)\\h+)?"
            + "[가-힣A-Za-z][가-힣A-Za-z0-9·ㆍ‧∙&.\\-\\h]{0,78}?(?:" + TYPES + ")|"
            + GOVERNMENT + "|" + REGION + "|[A-Z][A-Z0-9.-]{2,15})" + END);
    private static final Pattern CORPORATION = Pattern.compile("^(?:㈜|\\(주\\)|주식회사\\h+)[가-힣A-Za-z0-9][가-힣A-Za-z0-9.-]{1,50}" + END);
    private static final Pattern UNIT = Pattern.compile("^\\h+(?:[가-힣]{1,20}\\h*)?(?:산학협력단|운영본부)" + END);
    private static final Pattern SEPARATOR = Pattern.compile("^(?:[,/;·ㆍ‧∙&]\\h*|(?:및|와|과)\\h*)");
    private static final Pattern GENERIC = Pattern.compile("(?:(?:참여|책임|공동|소속|담당|실습|학생|신규|선임|수석)?연구원|연구책임자|담당자|직원|"
            + "(?:국내|국외|공공|민간|영리|비영리)?(?:연구기관|연구소|기업부설연구소|대학|대학교|의료기관|사무국|기업)|자치구|행정구|법정구)");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)예시|견본|가칭|미정|추후|모집|선정|기재|작성|입력|해당|각\\s*기관|전문기업|중소기업$|(?:^|[가-힣])(?:OO|XX)|[○□ㅇ]{2}");
    private static final Pattern NON_NAME_PREFIX = Pattern.compile("^(?:및|또는|와|과)(?=$|\\h)|^(?:(?:주관|공동|참여|국내|국외)\\h*)?(?:연구개발기관|연구기관)(?=$|\\h)|"
            + "^(?:주관기관|운영기관|전담기관|전문기관|문의처|접수처|주관|주최)(?=$|\\h|[:：])|"
            + "^(?:프로그램|사업비|총사업비|예산|정부보조금|기업당|운영예산|지원대상|신청대상)(?=$|\\h|[0-9])");
    private static final Pattern DOCUMENT_AFTER = Pattern.compile("^\\h*(?:인정서|확인서|증명서|재직증명서|등록증|신청서|동의서|서약서|계획서|보고서)(?=$|[\\h,;/|()])");
    private static final Pattern UNCERTAIN = Pattern.compile("예시|견본|가칭|예정|미정|추후|전년도|지난해|타\\h*사업|다른\\h*사업|참고\\h*사업|작성\\h*예시|예\\h*[:：]");
    private static final Pattern NEGATED = Pattern.compile("아님|아니(?:다|며|라|므로|고)|담당\\h*제외|제외됨|(?:담당|주관|운영)하지");
    private static final Pattern CHANGE = Pattern.compile("^(.+?)에서\\h+(.+?)(?:으로|로)\\h*변경(?:됨|되었음|되었습니다)?[.]?$");
    private static final Set<String> ABBREVIATIONS = Set.of("KOTRA", "KAIST", "KIAT", "KIST", "KEIT", "KOSME", "KEPCO", "KETI", "KITECH", "ETRI", "SBA");

    private ResponsibleOrganizationNames() {}

    static String normalize(String value) {
        return value.replace('\u00a0', ' ').replace("\u200b", "")
                .replace('（', '(').replace('）', ')').replace('：', ':');
    }

    static List<String> read(String value) {
        value = normalize(value).strip().replaceFirst("^[□◦○▪▫·•∙⦁■▶◈]+\\h*", "");
        if (value.length() > 500 || UNCERTAIN.matcher(value).find() || NEGATED.matcher(value).find()) return List.of();
        var change = CHANGE.matcher(value);
        if (change.matches()) {
            return complete(change.group(1)) && complete(change.group(2)) ? read(change.group(2)) : List.of();
        }
        var result = new ArrayList<String>();
        String remaining = value;
        while (!remaining.isBlank() && result.size() < 8) {
            if (remaining.startsWith("(") && !remaining.matches("^\\([재사주]\\).*")) remaining = remaining.substring(1).strip();
            if (NON_NAME_PREFIX.matcher(remaining).find()) break;
            var match = NAME.matcher(remaining);
            var corporation = CORPORATION.matcher(remaining);
            int end;
            if (corporation.find()) end = corporation.end();
            else if (match.find()) end = match.end();
            else break;
            var unit = UNIT.matcher(remaining.substring(end));
            if (unit.find()) end += unit.end();
            String candidate = remaining.substring(0, end).replaceAll("\\h+", " ").strip();
            String compact = candidate.replaceAll("\\h", "");
            String tail = remaining.substring(end);
            // A complete institution type or an explicit legal/administrative name is required.
            // In particular, 원/시/도 alone are not institution types (amounts and task words end in them).
            if (GENERIC.matcher(compact).matches() || PLACEHOLDER.matcher(candidate).find()
                    || compact.matches(".*(?:도구|요구|청구|탐구|기구)$")
                    || candidate.matches("[A-Z0-9.-]+") && !ABBREVIATIONS.contains(candidate)
                    || DOCUMENT_AFTER.matcher(tail).find()) break;
            result.add(candidate);
            remaining = tail.strip();
            if (remaining.startsWith("(")) {
                int close = remaining.indexOf(')');
                if (close < 0 || close > 80) break;
                remaining = remaining.substring(close + 1).strip();
            }
            if (remaining.startsWith(")")) remaining = remaining.substring(1).strip();
            var separator = SEPARATOR.matcher(remaining);
            if (!separator.find()) break;
            remaining = remaining.substring(separator.end()).strip();
        }
        return result;
    }

    /** An unlabelled following line can continue a list only if its entire content is a name. */
    static boolean complete(String value) {
        var names = read(value);
        if (names.size() != 1) return false;
        return normalize(value).strip().replaceFirst("^[□◦○▪▫·•∙⦁■▶◈]+\\h*", "")
                .replaceAll("\\h+", " ").equals(names.getFirst());
    }
}
