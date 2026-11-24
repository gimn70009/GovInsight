package com.publicmonitor.backend.domain.document.service;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

/** Label validation never changes a condition, its evidence or the saved model response. */
final class ParticipationContextLabels {
    private ParticipationContextLabels() {}

    static boolean structural(String value) {
        String label = value.replaceAll("\\s+", "").replaceFirst(
                "^(?:\\(?(?:[0-9]+(?:-[0-9]+)*|[가-힣]|[IVXⅠ-Ⅻ]+)[.)]|[①-⑳])", "");
        if (label.matches(".+(?:모집(?:공고|안내)?|선정공고|신청안내)")) return true;
        String[] parts = label.split("[-–—>›/:：·ㆍ|]+", -1);
        return Arrays.stream(parts).allMatch(part -> part.matches(
                "(?:사업|과제)?(?:개요|목적|내용|수행내용)|지원분야|수행내용"
                + "|(?:신청|지원|참여)?(?:자격|요건|대상)|지원내용|제출서류|신청서류"
                + "|신청방법|접수방법|평가방법|평가기준|선정기준|평가|유의사항"
                + "|(?:신청)?자격(?:적정성)?확인서|신청서|신청서식|서식|작성예시"));
    }

    static String audience(String value, String evidence) {
        if (value == null || value.isBlank() || structural(value)) return null;
        String label = value.strip();
        String cited = evidence.replaceAll("\\s+", "");
        if (supported(label, cited)) return label;
        var gloss = Pattern.compile("([^()（）]+)[(（]([^()（）]+)[)）]").matcher(label);
        if (gloss.matches()) {
            String core = gloss.group(1).strip();
            if (supported(core, cited)) return core;
            if (Set.of("신청자", "신청기관", "신청기업").contains(core)
                    && supported(gloss.group(2), cited)) return gloss.group(2).strip();
        }
        String applicant = label.replaceFirst("\\s+신청자$", "");
        return !applicant.equals(label) && supported(applicant, cited) ? applicant : null;
    }

    private static boolean supported(String label, String cited) {
        var words = Arrays.stream(label.split("[\\s·ㆍ‧,/()（）]+"))
                .filter(word -> !word.isBlank() && !Set.of("및", "또는", "와", "과", "등").contains(word)).toList();
        return !words.isEmpty() && words.stream().allMatch(cited::contains);
    }
}
