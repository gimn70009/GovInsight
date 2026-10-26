package com.publicmonitor.backend.domain.document.service;

import java.util.regex.Pattern;

/** Readability changes only: subjects, dates, quantities, exceptions and source excerpts stay intact. */
final class SubmissionNoteFormatter {
    private static final Pattern EMPTY = Pattern.compile("^[（(]?\\h*(?:제출|구비)\\h*서류\\h*(?:없음|없습니다|해당\\h*없음|불필요)\\h*[)）]?[.!。]?$");
    private static final Pattern ISSUED = Pattern.compile("^(.+?)(\\d+\\h*(?:개월|일|년)\\h*이내)\\h*발급분[.!。]?$");
    private static final Pattern VALID = Pattern.compile("^(.+?기준)\\h*기간\\h*유효\\h*(?:必|필수)(?:,\\h*(.+?)\\h*명시\\h*(?:必|필수))?[.!。]?$");
    private static final Pattern PAGE_GUIDE = Pattern.compile("^가점\\h*항목별\\h*제출서류가\\h*상이하므로,?\\h*(\\d+)(?:p|쪽)\\h*가점항목\\h*참고[.!。]?$");

    private SubmissionNoteFormatter() {}

    static String clean(String raw) {
        return raw.replaceFirst("^[*※◦•]+\\h*", "").replaceAll("\\s+", " ").strip();
    }

    static boolean empty(String raw) {
        return EMPTY.matcher(clean(raw)).matches();
    }

    static boolean actionable(String raw) {
        String value = clean(raw);
        return !empty(value) && value.length() <= 1000
                && value.matches(".*(?:서류|제출|증빙|발급|유효|날인|서명|파일|첨부|가점).*")
                && value.matches(".*(?:제출|발급|유효|날인|서명|필수|必|인정|면제|생략|참고|확인|보완|명시).*");
    }

    static boolean common(String raw) {
        return clean(raw).matches(".*(?:모든|전체|공통)\\h*(?:작성)?서류.*");
    }

    static boolean localCondition(String raw) {
        return clean(raw).matches("^(?:신청일|접수일|접수\\h*마감일|신청\\h*마감일|마감일|공고일|발급일|제출일|유효기간|기간|원본|사본|서명|날인|직인).*");
    }

    static boolean selfContained(String raw) {
        String value = clean(raw);
        // A local date/validity fragment is meaningful only with a reliably identified document row.
        return common(value)
                || value.matches("^가점.*")
                || value.matches(".*(?:서류|신청서|증명서|확인서|등록증|동의서|요구서).*(?:제출|날인|첨부|보완|입력|작성).*");
    }

    static String label(String raw) {
        return clean(raw).replace("必", "필수").replace("內", "내").replace("中", "중");
    }

    static String format(String raw) {
        String value = clean(raw.replaceAll("\\R\\h*-\\h*", ". "));
        var issued = ISSUED.matcher(value);
        if (issued.matches()) return issued.group(1) + issued.group(2) + "에 발급된 서류여야 합니다.";
        var valid = VALID.matcher(value);
        if (valid.matches()) return valid.group(1) + "으로 유효한 서류여야 합니다."
                + (valid.group(2) == null ? "" : " ‘" + valid.group(2) + "’임이 명시되어 있어야 합니다.");
        var pageGuide = PAGE_GUIDE.matcher(value);
        if (pageGuide.matches()) return "가점 항목마다 필요한 서류가 다릅니다. " + pageGuide.group(1) + "쪽의 가점 항목을 확인해 주세요.";
        value = value.replace("제공 미요구시", "제공을 요구하지 않은 경우,")
                .replaceAll("아래[（(](\\d+[~∼-]\\d+)번[)）]", "원문 $1번의")
                .replaceAll("제출\\h*필수(?=\\.|$)", "제출이 필요합니다");
        value = value.replaceAll("검토하며, 한도는 (최대 \\d+점),", "검토합니다. 한도는 $1입니다.");
        value = value.replaceAll("(\\d+)p(?=\\h|$)", "$1쪽")
                .replace("必", "필수").replace("內", "내").replace("中", "중")
                .replaceAll("발급분(?=\\h|$)", "발급된 서류")
                .replace("유효서류", "유효한 서류");
        // Expand terse endings without changing a condition into an unconditional requirement.
        value = value.replaceFirst("불인정$", "인정되지 않습니다")
                .replaceFirst("(?<!불)인정$", "인정됩니다")
                .replaceFirst("참고$", "확인해 주세요")
                .replaceFirst("제출\\h*필수$", "제출이 필요합니다")
                .replaceFirst("보완요청\\h*가능$", "보완을 요청할 수 있습니다");
        return value.matches(".*[.!?。]$") ? value : value + ".";
    }
}
