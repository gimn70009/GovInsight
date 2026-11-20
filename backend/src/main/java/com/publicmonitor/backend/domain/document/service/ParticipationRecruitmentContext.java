package com.publicmonitor.backend.domain.document.service;

import java.util.List;
import java.util.regex.Pattern;

/** Verbatim recruitment context for new model requests; never part of paid-cache identity. */
final class ParticipationRecruitmentContext {
    private static final Pattern RECRUITMENT = Pattern.compile(
            "(?:기관|기업|단체|조합|컨소시엄|사업자|대학).{0,45}모집(?!\\h*제한)|모집(?!\\h*제한).{0,30}(?:기관|기업|단체|조합|사업자|대학)");
    private static final Pattern SEPARATE = Pattern.compile("별도|추후|예정");

    private ParticipationRecruitmentContext() {}

    static String from(String document) {
        String text = document;
        int restart = text.indexOf("[문서 전체 텍스트 추출]");
        if (restart >= 0) text = text.substring(restart + "[문서 전체 텍스트 추출]".length());
        List<String> candidates = text.substring(0, Math.min(1800, text.length())).lines()
                .map(String::strip).filter(line -> line.length() <= 240)
                .filter(line -> RECRUITMENT.matcher(line).find()).distinct().toList();
        if (candidates.isEmpty()) return "";
        String first = candidates.getFirst();
        // Keep a later/separate recruitment next to the current title when it is stated.
        String recruitment = candidates.stream().skip(1).filter(line -> SEPARATE.matcher(line).find()).findFirst()
                .map(separate -> first + " / " + separate).orElse(first);
        // An overview may introduce individual calls later. Preserve its original title as context.
        return text.substring(0, Math.min(1800, text.length())).lines().map(String::strip)
                .filter(line -> line.length() <= 120 && line.matches(".*통합\\h*(?:공고|모집).*") && !recruitment.contains(line))
                .findFirst().map(title -> title + " / " + recruitment).orElse(recruitment);
    }
}
