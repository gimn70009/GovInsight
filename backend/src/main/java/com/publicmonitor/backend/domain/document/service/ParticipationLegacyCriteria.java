package com.publicmonitor.backend.domain.document.service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Frozen v1 vocabulary used only to reuse paid legacy results without another call. */
enum ParticipationLegacyCriteria {
    BUSINESS_AGE("업력", "업력|창업\\s*(?:후|일|[0-9])|설립|개업|회사성립|사업자\\s*등록|사업\\s*개시"),
    REVENUE("매출", "매출|revenue|sales"),
    IP("특허", "특허|patent"),
    SECTOR("유관산업", "업종|업태|(?:유관|관련)\\s*산업|(?:산업|지원|모집|사업)\\s*분야|KSIC|한국표준산업분류"
            + "|제조|바이오|반도체|정보통신|인공지능|소프트웨어|이차전지|모빌리티|로봇|(?<![A-Za-z])(?:AI|ICT)(?![A-Za-z])"
            + "|(?:대상|신청|참여).{0,30}분야|분야.{0,30}(?:기업|기관|보유|실적)"
            + "|납품\\s*(?:이력|실적|경험)|(?:관련|유관)\\s*분야.{0,30}(?:경험|실적)|영위");

    final String label;
    private final Pattern topic;

    ParticipationLegacyCriteria(String label, String expression) {
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
