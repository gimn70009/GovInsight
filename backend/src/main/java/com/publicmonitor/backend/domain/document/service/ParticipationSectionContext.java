package com.publicmonitor.backend.domain.document.service;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Keeps a nested application heading inside its enclosing post-selection section. */
final class ParticipationSectionContext {
    private static final String EXCLUDED = "지원조건|지원내용|지원규모|지원기간|지원금액|지원비율|지원한도"
            + "|사업비(?:구성|지원|부담|사용|집행|관리)?|(?:연구개발과제)?연구개발비(?:구성|지원기준|지원|부담|사용|집행|관리)"
            + "|정부지원연구개발비지원기준|협약(?:체결|기간|및사후관리)?|사후관리|수행중의무"
            + "|제출서류|신청서류|구비서류|선정절차|평가방법|평가기준|평가및선정|선정평가";
    private static final Pattern EXCLUDED_TITLE = Pattern.compile(
            "^(?:" + EXCLUDED + ")(?:[)）]?[:：].*|[)）]?|[（(].*[)）])$");
    private final boolean categorized;
    ParticipationSectionContext() { this(false); }
    ParticipationSectionContext(boolean categorized) { this.categorized = categorized; }
    private Integer excludedLevel;
    private boolean pendingNumber;
    private final Set<Integer> establishedLevels = new HashSet<>();

    boolean excludes(String raw) {
        String line = raw.strip();
        if (line.startsWith("[파일:")) {
            excludedLevel = null;
            pendingNumber = false;
            establishedLevels.clear();
            return false;
        }
        if (line.matches("\\d{1,2}(?:-\\d+)?")) {
            pendingNumber = true;
            return excludedLevel != null;
        }
        boolean splitNumber = pendingNumber && line.length() < 60
                && !line.matches("^[□■○◦●ㅇ*※-].*");
        if (!line.isBlank()) pendingNumber = false;
        String title = line.replaceFirst("^(?:[Ⅰ-ⅩIVX]+[.)]?\\h+|\\d+(?:-\\d+)*[.)]\\h*|\\d+(?:-\\d+)*\\h+)", "")
                .replaceFirst("^(?:[가-하][.)]|[□■○◦●ㅇ▶▷])\\h*", "")
                .replaceAll("\\s+", "").replaceFirst("^[<〈(（]", "")
                .replaceFirst("[>〉]$", "");
        boolean excluded = EXCLUDED_TITLE.matcher(title).matches()
                || title.matches("기술료.*(?:감면|납부유예).*");
        if (categorized && title.matches("(?:평가방법|평가기준|평가및선정|선정평가|선정절차|제출서류|신청서류|구비서류|지원조건|지원내용|정부지원연구개발비지원기준|사업비(?:구성|부담)|연구개발비(?:구성|부담|지원기준))(?:[:：].*)?"))
            excluded = false;
        boolean numbered = splitNumber || line.matches("^(?:\\d+(?:-\\d+)*[.)]\\h*|\\d+(?:-\\d+)*\\h+|[Ⅰ-ⅩIVX]+[.)]?\\h+).*");
        boolean letter = line.matches("^[가-하][.)]\\h*.*");
        boolean square = line.matches("^[□■▶▷].*");
        boolean bullet = line.matches("^[○◦●ㅇ]\\h+.*");
        boolean named = title.matches("(?:신청|지원|참여|모집)(?:자격|대상|요건|제외|제외대상|제한)(?:[:：].*)?")
                || title.matches("사업개요|사업목적|사업내용|신청방법|접수방법|신청기간|추진일정|문의처|기타사항");
        if (!excluded && !numbered && !letter && !square && !named) return excludedLevel != null;
        int level = numbered ? (line.matches("^\\d+-.*") ? 15 : 10)
                : letter ? 20 : square ? 30 : bullet ? 40 : 30;
        if (excludedLevel != null && (level == excludedLevel
                || (level < excludedLevel && establishedLevels.contains(level)))) excludedLevel = null;
        if (excludedLevel == null) establishedLevels.add(level);
        if (excluded && excludedLevel == null) excludedLevel = level;
        return excludedLevel != null;
    }
}
