package com.publicmonitor.backend.domain.report;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Selects existing purpose prose only when a separate field already covers the removed detail. */
final class ReportSummaryFocus {
    private static final String ADMIN_START = "(?:(?:국문|영문|국내|해외|한국|일본)\\s*)?(?:의견\\s*제출|신청|접수|제출)\\s*(?:기간|기한|마감|방법|방식|은|는)";
    private static final String AMOUNT_START = "(?:(?:과제|기업|업체)(?:당|별)|정부\\s*(?:지원금|출연금)|지원\\s*(?:규모|한도|금액|예산|금|기간)|총\\s*(?:사업비|예산)|사업비|연구개발비|과제\\s*기간|연구개발\\s*기간)";
    private static final Pattern ADMIN = Pattern.compile("^(?:" + ADMIN_START + "|마감일|제출일)");
    private static final Pattern AMOUNT = Pattern.compile("^" + AMOUNT_START);
    private static final Pattern DETAIL_BOUNDARY = Pattern.compile("[,;]\\s*(?=" + AMOUNT_START + "|" + ADMIN_START + ")");
    private static final Pattern MEANING = Pattern.compile("변경|개정|확대|축소|연장|전환|폐지|정책|목적|목표|위해|위한|사업화|공동연구|분야|RFP|연구실|자격|지원대상|모집대상|평가기준|매출|부채|다만|예외|조건|불가|제외|필수|반드시");
    private static final Pattern LINKS = Pattern.compile("https?://[^\\s<>]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(20\\d{2})\\s*(?:년\\s*|[./-]\\s*)(\\d{1,2})\\s*(?:월\\s*|[./-]\\s*)(\\d{1,2})\\s*일?");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,:/~-]\\d+)*(?:\\s*%)?");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*|[가-힣]+");
    private static final Pattern ENDINGS = Pattern.compile("(?:까지이며|까지입니다|입니다|합니다|됩니다|하며|하고|이며|이고|까지|부터|으로|에서|은|는|을|를|로)$");
    private static final Set<String> SCHEDULE_WORDS = Set.of("의견", "제출", "신청", "접수", "기한", "기간", "마감", "마감일", "제출일", "방법", "방식", "일정", "진행", "일", "년", "월", "시", "분");
    private ReportSummaryFocus() {}

    static List<String> select(List<String> sentences, List<ReportSchedulePresentation.Fact> amounts,
                               List<ReportSchedulePresentation.Fact> schedule) {
        var kept = new ArrayList<String>();
        for (String original : sentences) {
            if (covered(original, amounts, schedule)) continue;
            String sentence = original;
            var boundary = DETAIL_BOUNDARY.matcher(original);
            while (boundary.find()) {
                String before = closeClause(original.substring(0, boundary.start()));
                if (before != null && covered(original.substring(boundary.end()), amounts, schedule)) {
                    sentence = before;
                    break;
                }
            }
            kept.add(sentence);
        }
        // A detail-only saved summary has no source-backed purpose to substitute.
        return kept.isEmpty() ? sentences : List.copyOf(kept);
    }

    private static boolean covered(String value, List<ReportSchedulePresentation.Fact> amounts,
                                   List<ReportSchedulePresentation.Fact> schedule) {
        if (MEANING.matcher(value).find()) return false;
        if (AMOUNT.matcher(value).find()) {
            String detail = exactKey(value);
            return !detail.isBlank() && amounts.stream().filter(fact -> valid(fact.value()))
                    .anyMatch(fact -> exactKey(fact.value()).contains(detail));
        }
        if (!ADMIN.matcher(value).find()) return false;
        var deadlines = new ArrayList<String>();
        var destinations = new ArrayList<String>();
        for (var fact : schedule) {
            if (!valid(fact.value())) continue;
            if (fact.deadline()) {
                for (String row : fact.value().split("\\R|\\s+/\\s+")) deadlines.add(row);
            } else if (fact.label().equals("제출처·방법")) {
                for (String row : fact.value().split("\\R")) destinations.add(row);
            }
        }
        boolean temporal = DATE.matcher(value).find() || value.matches(".*(?:기한|마감|기간).*");
        boolean route = value.matches(".*(?:우편|이메일|온라인|방문|https?://|@).*");
        if (temporal) {
            for (String deadline : deadlines) {
                // Keep date scope independent of a route that happens to name another country.
                if (!sameScheduleScope(value, deadline)) continue;
                if (!route && scheduleCovers(value, deadline)) return true;
                for (String destination : destinations) {
                    if (scheduleCovers(value, deadline + " " + destination)) return true;
                }
            }
            return false;
        }
        return destinations.stream().anyMatch(destination -> scheduleCovers(value, destination));
    }

    private static boolean sameScheduleScope(String value, String deadline) {
        Pattern roles = Pattern.compile("국문|영문|국내|해외|한국|일본|스페인|스웨덴|영국|\\d+차");
        var expected = roles.matcher(value).results().map(match -> match.group()).toList();
        var known = roles.matcher(deadline).results().map(match -> match.group()).toList();
        if (!known.containsAll(expected)) return false;
        for (String marker : List.of("부터|시작|개시", "까지|마감|기한")) {
            Pattern edge = Pattern.compile(marker);
            if (edge.matcher(value).find() != edge.matcher(deadline).find()) return false;
        }
        return true;
    }

    private static boolean scheduleCovers(String value, String context) {
        var anchors = anchors(value);
        var words = words(value);
        if (anchors.isEmpty() && words.stream().noneMatch(word -> Set.of("우편", "이메일", "온라인", "방문").contains(word))) return false;
        if (!anchors(context).containsAll(anchors) || !words(context).containsAll(words)) return false;
        // Alternative, sequential and simultaneous submissions are different obligations.
        for (String connector : List.of("또는|혹은", "(?:제출|접수|우편|온라인)\\s*(?:후|이후)", "동시|함께|병행")) {
            Pattern pattern = Pattern.compile(connector);
            if (pattern.matcher(value).find() != pattern.matcher(context).find()) return false;
        }
        return true;
    }

    private static boolean valid(String value) {
        return !ReportOpportunityPresentation.unknownOnly(value)
                && !value.contains("원문에서 확인") && !value.contains("원문 표에서")
                && !ReportFactPresentation.unreadableTable(value);
    }

    private static String exactKey(String value) {
        return value.replaceAll("\\s+", "").replaceFirst("[.!。]+$", "");
    }

    private static String closeClause(String value) {
        String text = value.strip();
        if (text.matches(".*[다요][.!?]$")) return text;
        if (text.matches(".*(?:이며|이고|으로|로)$")) return text.replaceFirst("(?:이며|이고|으로|로)$", "입니다.");
        if (text.matches(".*(?:하며|하고)$")) return text.replaceFirst("(?:하며|하고)$", "합니다.");
        return null;
    }

    private static Set<String> anchors(String value) {
        var result = new LinkedHashSet<String>();
        LINKS.matcher(value).results().forEach(match -> result.add(match.group().replaceFirst("[.,;:)]$", "")));
        String text = LINKS.matcher(value).replaceAll(" ");
        var dates = DATE.matcher(text);
        while (dates.find()) result.add("date:" + dates.group(1) + "-" + Integer.parseInt(dates.group(2)) + "-" + Integer.parseInt(dates.group(3)));
        NUMBER.matcher(dates.replaceAll(" ")).results().forEach(match -> result.add(match.group().replaceAll("\\s+", "")));
        return result;
    }

    private static Set<String> words(String value) {
        String text = NUMBER.matcher(DATE.matcher(LINKS.matcher(value).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
        var result = new LinkedHashSet<String>();
        WORD.matcher(text).results().forEach(match -> {
            String word = match.group();
            String previous;
            do { previous = word; word = ENDINGS.matcher(word).replaceFirst(""); } while (!word.equals(previous));
            if (word.equals("전자우편")) word = "이메일";
            if (!word.isBlank() && !SCHEDULE_WORDS.contains(word)) result.add(word);
        });
        return result;
    }
}
