package com.publicmonitor.backend.domain.document.service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Topics used to select evidence, never to infer a mandatory or preferential condition. */
enum ParticipationFocusCriterion {
    BUSINESS_AGE("업력", "업력|창업\\s*(?:후|일|[0-9])|설립|개업|회사성립|사업자\\s*등록|사업\\s*개시"),
    REVENUE("매출·수출", "매출|수출액|수출실적|수입\\s*금액|revenue|sales"),
    IP("특허·인증·지정", "특허|patent|인증|지정|기술개발제품"),
    SECTOR("업종·품목·서비스", "업종|업태|(?:유관|관련)\\s*산업|(?:산업|지원|모집|사업)\\s*분야|KSIC|한국표준산업분류"
            + "|제조|바이오|반도체|정보통신|인공지능|소프트웨어|이차전지|모빌리티|로봇|(?<![A-Za-z])(?:AI|ICT)(?![A-Za-z])"
            + "|(?:대상|신청|참여).{0,30}분야|분야.{0,30}(?:기업|기관|보유|실적)"
            + "|경제안보|품목|서비스|영위"),
    BUSINESS_FORM("신청 주체", "중소기업|소상공인|중견기업|사업자단체|공공기관|민간기관|전문기관|전문\\s*기관|법인|단체|산학연협력기술지주회사|신기술창업전문회사|벤처투자회사|창업기획자"),
    EXPERIENCE("실적·수행 역량", "계약실적|계약\\s*실적|납품|수행\\s*(?:경험|실적|역량)|지원기능|지원\\s*기능|조달\\s*역량|실증"),
    CREDIT("신용·재무 요건", "신용|부채비율|자본잠식|부채\\s*비율|자본\\s*잠식");

    final String label;
    private final Pattern topic;

    ParticipationFocusCriterion(String label, String expression) {
        this.label = label;
        this.topic = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
    }

    static List<String> matching(String excerpt, String heading) {
        String topicHeading = heading == null ? "" : heading;
        int section = topicHeading.indexOf("[원문 구역] ");
        if (section >= 0) topicHeading = topicHeading.substring(section + "[원문 구역] ".length());
        String source = topicHeading + "\n" + excerpt;
        return Arrays.stream(values()).filter(c -> c.topic.matcher(source).find()).map(Enum::name).toList();
    }

    static boolean supports(String code) {
        return Arrays.stream(values()).anyMatch(c -> c.name().equals(code));
    }
}
