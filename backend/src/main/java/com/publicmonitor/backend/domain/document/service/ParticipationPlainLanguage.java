package com.publicmonitor.backend.domain.document.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Explains fully recognized criteria; unrecognized qualifications remain intact. */
final class ParticipationPlainLanguage {
    private static final String LAW = "[｢「『][^｢「『｣」』]+[｣」』]";
    private static final String ARTICLE = "제\\d+조(?:의\\d+)?(?:\\h*제\\d+[항호목])*(?:\\([^()]*\\))?";
    private static final Pattern LEGAL_BASIS = Pattern.compile(
            "(" + LAW + "|동법)\\h*(" + ARTICLE + ")\\h*에\\h*따른\\h*");
    private static final Pattern ROLE = Pattern.compile("^(?:\\(([^()]{2,20})\\)\\h*|([가-힣·]{2,20})\\h*[:：]\\h*)");
    private static final String AGE = "창업\\h*후\\h*\\d+\\h*년\\h*(?:이내|미만|이상|이하|초과)\\h*(?:(?:[가-힣]+\\h+){1,3}분야\\h+)?(?:창업[·ㆍ]중소기업|창업기업|중소기업|기업)";
    private static final Pattern STARTUP = Pattern.compile(
            "^(?:(중소기업)의\\h*대표자이자,?\\h*)?"
            + "((?:모집\\h*)?공고일|신청\\h*마감일|접수\\h*마감일)\\h*기준\\h*"
            + "(" + AGE + ")(?:\\h+(또는|및)\\h+(" + AGE + "))?"
            + "(?:으로\\h*공고문의?\\h*신청\\h*자격\\h*및\\h*요건을\\h*충족하는\\h*기업)?"
            + "(?:이\\h*대상입니다|입니다)?[.]?$");
    private ParticipationPlainLanguage() {}

    record Explanation(String text, String label, List<String> legalDetails) {}

    static Explanation explain(String text, String fallbackLabel) {
        String body = text;
        String role = null;
        var roleMatch = ROLE.matcher(body);
        if (roleMatch.find()) {
            role = roleMatch.group(1) == null ? roleMatch.group(2) : roleMatch.group(1);
            body = body.substring(roleMatch.end());
        }
        var legal = LEGAL_BASIS.matcher(body);
        var laws = new ArrayList<String>();
        var lawOffsets = new ArrayList<Integer>();
        String lastLaw = null;
        StringBuilder plain = new StringBuilder();
        int previous = 0;
        while (legal.find()) {
            String law = legal.group(1);
            if (law.equals("동법")) {
                if (lastLaw == null) return null;
                law = lastLaw;
            } else lastLaw = law;
            plain.append(body, previous, legal.start());
            lawOffsets.add(plain.length());
            laws.add(law + " " + legal.group(2));
            previous = legal.end();
        }
        plain.append(body.substring(previous));
        String candidate = plain.toString().strip().replaceFirst(
                "으로\\h*공고문의?\\h*신청\\h*자격\\h*및\\h*요건을\\h*충족하는\\h*기업(?:이 대상입니다)?[.]?$", "");
        var startup = STARTUP.matcher(candidate);
        if (startup.matches() && (role == null || role.equals("창업기업"))) {
            var lines = new ArrayList<String>();
            if (startup.group(1) != null) lines.add("신청 대상은 " + startup.group(1) + " 대표자입니다.");
            String date = startup.group(2).replaceAll("\\h+", "");
            if (startup.group(4) == null) lines.add(date + " 기준, " + startup.group(3) + "이어야 합니다.");
            else {
                lines.add(date + " 기준, 다음 " + (startup.group(4).equals("또는") ? "중 하나에 해당해야" : "조건을 모두 충족해야") + " 합니다.");
                lines.add("• " + startup.group(3));
                lines.add("• " + startup.group(5));
            }
            // Keep each citation bound to its original predicate, rather than dropping legal definitions.
            var subjects = new ArrayList<String>();
            if (startup.group(1) != null) subjects.add(startup.group(1) + " 대표자");
            subjects.add(startup.group(3));
            if (startup.group(5) != null) subjects.add(startup.group(5));
            var definitions = new ArrayList<String>();
            var subjectOffsets = new ArrayList<Integer>();
            if (startup.group(1) != null) subjectOffsets.add(0);
            subjectOffsets.add(startup.start(3));
            if (startup.group(5) != null) subjectOffsets.add(startup.start(5));
            if (lawOffsets.equals(subjectOffsets)) {
                for (int i = 0; i < laws.size(); i++) definitions.add("법적 자격 기준: " + subjects.get(i) + " — " + laws.get(i));
            } else definitions.addAll(legalDetails(body, laws));
            return new Explanation(String.join("\n", lines), "창업기업 자격", definitions);
        }
        // A standalone legal definition can be explained without carrying article numbers in its headline.
        if (role == null && laws.size() == 1 && LEGAL_BASIS.matcher(body).lookingAt()
                && candidate.matches("(?:중소기업|소기업|소상공인|소공인|법인[·ㆍ]단체 또는 기관)(?:이 대상입니다|입니다)?[.]?")) {
            String summary = candidate.matches(".*입니다[.]?$") ? candidate : candidate.replaceFirst("[.]$", "") + "이 대상입니다.";
            return new Explanation(summary, fallbackLabel, legalDetails(body, laws));
        }
        return null;
    }

    private static List<String> legalDetails(String body, List<String> laws) {
        if (laws.isEmpty()) return List.of();
        // This one complete sentence retains which legal article defines which alternative.
        return List.of("법적 자격 기준: " + body.replaceFirst("(?:이 대상입니다|입니다)[.]?$", "").replaceFirst(
                "으로\\h*공고문의?\\h*신청\\h*자격\\h*및\\h*요건을\\h*충족하는\\h*기업(?:이 대상입니다)?[.]?$", ""));
    }
}
