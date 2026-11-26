package com.publicmonitor.backend.domain.document.service;

import java.util.regex.Pattern;

/** Syntax shared by explicit submission lists; broader noun labels stay inside that context. */
final class SubmissionListSyntax {
    private static final Pattern MARKER = Pattern.compile("^(?:(?:[①-⑳□■○◦●ㅇ•*-]|[0-9]+[.)]|[가-하][.)]|[（(][0-9]+[)）])\\h*)+");
    private static final Pattern NUMBERED = Pattern.compile("^(?:[-•]\\h*)?(?:[①-⑳]|[0-9]+[.)]|[가-하][.)]|[（(][0-9]+[)）])");
    private static final Pattern ANNOTATION = Pattern.compile("\\h+[*※]\\h*");
    private static final Pattern NOMINAL = Pattern.compile("^.{0,150}?(?:정관|규약|약정|협약|명부|목록|현황|내역|증명서류|증명자료)(?:\\h*등)?(?:\\h*[（(][^\\n]{0,100}[)）])?(?:\\h*(?:각\\h*)?\\d+\\h*(?:부|매))?$");
    private static final Pattern REFERENCE = Pattern.compile("^[（(\\[].{0,80}(?:참조|참고|별첨|별지|붙임|서식|양식).{0,20}[)）\\]]$");

    private SubmissionListSyntax() {}
    static String unmarked(String line) { return MARKER.matcher(line.strip()).replaceFirst("").strip(); }
    static String title(String line) {
        String value = unmarked(line);
        var note = ANNOTATION.matcher(value);
        if (note.find()) value = value.substring(0, note.start());
        return SubmissionDocumentResolver.cleanTitle(value);
    }
    static String annotation(String line) {
        var note = ANNOTATION.matcher(line);
        return note.find() ? line.substring(note.end()).strip() : "";
    }
    static boolean reference(String text) {
        return REFERENCE.matcher(text.strip()).matches()
                && !text.matches(".*(?:해당|경우|한함|한하여|한해|제외|면제|선택|불필요|만[)）]).*");
    }
    static boolean group(String text) { return unmarked(text).matches("(?:첨부|추가|구비|제출)\\h*(?:서류|자료)(?:\\h*[（(][^\\n]{1,100}[)）])?\\h*[:：]?"); }
    static String marker(String line) {
        var marker = NUMBERED.matcher(line.strip());
        if (!marker.find()) return "";
        String value = marker.group().replaceFirst("^[-•]\\h*", "");
        return value.replaceAll("[0-9]+", "0").replaceAll("[①-⑳]", "①").replaceAll("[가-하]", "가");
    }
    static boolean document(String line) {
        if (line.strip().matches("^[*※].*") || group(line)) return false;
        String title = title(line);
        return SubmissionDocumentResolver.isDocument(title)
                || (NUMBERED.matcher(line.strip()).find() && NOMINAL.matcher(title).matches());
    }
}
