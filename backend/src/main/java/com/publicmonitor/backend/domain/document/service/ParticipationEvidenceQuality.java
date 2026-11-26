package com.publicmonitor.backend.domain.document.service;

import java.util.regex.Pattern;

/** Shared with Python grounding.py; exact citation presence alone is insufficient. */
final class ParticipationEvidenceQuality {
    private static final Pattern DOCUMENT = Pattern.compile("법인설립허가증|비영리민간단체등록증|사업자등록증(?:명원)?|고유번호증"
            + "|법인등기부등본|법인등기사항증명서|서약서|동의서|납세증명서|인감증명서");
    private static final Pattern PROCUREMENT = Pattern.compile("우선조달계약|우선구매|계약방법|입찰방식|수의계약");
    private static final Pattern DENIAL = Pattern.compile("(?:신청|참여|지원|입찰참가)(?:할수없|불가|불가능|제한|제외|자격이없|할수없는)");
    private static final Pattern LIST = Pattern.compile("신청서류|제출서류|구비서류");
    private static final Pattern QUALIFICATION = Pattern.compile("(?:등록|허가|인가|지정)(?:을받|받|된|한)|자격|참여제한|등록되어|등록돼");

    static String rejection(String text, String quote, String kind, String heading, boolean detail) {
        text = compact(text); quote = compact(quote); heading = compact(heading);
        var documents = DOCUMENT.matcher(text);
        while (documents.find()) if (!quote.contains(documents.group())) return "DOCUMENT_QUOTE_MISMATCH";
        if (detail) return null;
        if ("EXCLUSION".equals(kind) && PROCUREMENT.matcher(quote).find() && quote.contains("예외")
                && !DENIAL.matcher(quote).find()) return "PROCUREMENT_EXCEPTION";
        if ("ELIGIBILITY".equals(kind) && LIST.matcher(heading).find() && DOCUMENT.matcher(quote).find()
                && !QUALIFICATION.matcher(quote).find() && !DENIAL.matcher(quote).find()) return "DOCUMENT_LIST_NOT_QUALIFICATION";
        return null;
    }

    private static String compact(String value) { return value == null ? "" : value.replaceAll("\\s+", ""); }
}
