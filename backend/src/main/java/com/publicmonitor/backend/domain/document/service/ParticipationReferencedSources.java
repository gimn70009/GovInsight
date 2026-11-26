package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import java.util.*;
import java.util.regex.Pattern;

/** Recovers explicit references within one document without changing paid source identities. */
final class ParticipationReferencedSources {
    static final String MARKER = "[자격 참조 원문]";
    private static final Pattern SECTION = Pattern.compile("(?m)^[\\h□■]*(?:\\d+[.)]\\h*)?(?:신청|지원|참여)\\h*(?:자격|요건|대상)[^\\r\\n]*");
    private static final Pattern END = Pattern.compile("(?m)^[\\h]*(?:[□■]|[Ⅰ-Ⅻ]+[.]?\\h|\\d+[.]\\h)[^\\r\\n]*");
    private static final Pattern REFERENCE = Pattern.compile("별표\\h*(\\d*)(?:\\h*[（(]([^）)]+)[）)])?");
    private static final Pattern ANNEX = Pattern.compile("(?m)^\\h*(?:[■□][^\\r\\n]{0,250}?)?\\[별표\\h*(\\d*)][^\\r\\n]*");
    private static final Pattern STOP = Pattern.compile("(?m)^\\h*(?:[■□]?[^\\r\\n]{0,180}\\[(?:별표|별지)[^]\\r\\n]*]|(?:붙임|별첨|별지|서식)\\h*\\d+|\\[(?:문서 전체|PDF )).*");

    private static final Pattern RESET = Pattern.compile("(?m)^\\h*\\[(?:문서 전체 텍스트 추출|PDF \\d+페이지 시각 판독)]");
    private static final Pattern FORM = Pattern.compile("(?m)^\\h*(?:(?:별지|서식|양식)\\h*\\d+|[■□][^\\r\\n]*\\[별지)");

    static List<RequirementSource> collect(String document, String file) {
        var result = new ArrayList<RequirementSource>();
        var sections = SECTION.matcher(document);
        while (sections.find()) {
            int start = sections.start();
            int segmentStart = 0;
            var reset = RESET.matcher(document);
            while (reset.find() && reset.start() < start) segmentStart = reset.end();
            if (FORM.matcher(document).region(segmentStart, start).find()) continue;
            var end = END.matcher(document);
            int stop = end.find(sections.end()) ? end.start() : document.length();
            var formEnd = FORM.matcher(document);
            if (formEnd.find(sections.end())) stop = Math.min(stop, formEnd.start());
            var extractionEnd = RESET.matcher(document);
            if (extractionEnd.find(sections.end())) stop = Math.min(stop, extractionEnd.start());
            String excerpt = document.substring(start, stop);
            // Numbered definitions must have an inline callout in the same qualification block.
            var notes = Pattern.compile("(?m)^\\h*(\\d{1,2})\\)\\h+.+").matcher(excerpt);
            boolean linked = false;
            while (notes.find()) if (Pattern.compile("[가-힣A-Za-z%]" + notes.group(1) + "\\)")
                    .matcher(excerpt.substring(0, notes.start())).find()) linked = true;
            if (linked) add(result, file, sections.group(), excerpt);

            var refs = REFERENCE.matcher(excerpt);
            while (refs.find()) {
                String number = refs.group(1), title = refs.group(2);
                var law = Pattern.compile("[「｢]([^」｣]+)[」｣]").matcher(excerpt.substring(0, refs.start()));
                String regulation = "";
                while (law.find()) regulation = compact(law.group(1));
                var annexes = ANNEX.matcher(document);
                while (annexes.find()) {
                    if (!number.equals(annexes.group(1))) continue;
                    int annexEnd = document.length();
                    var next = STOP.matcher(document);
                    if (next.find(annexes.end())) annexEnd = next.start();
                    String block = document.substring(annexes.start(), annexEnd);
                    String header = compact(block.substring(0, Math.min(700, block.length())));
                    // A bare external reference cannot identify an arbitrary local appendix.
                    if (!regulation.isEmpty() && !header.contains(regulation)) continue;
                    if (number.isEmpty() && (title == null || !header.contains(compact(title)))) continue;
                    if (regulation.isEmpty() && title == null) continue;
                    add(result, file, sections.group(), block);
                }
            }
        }
        return result.stream().distinct().toList();
    }

    private static void add(List<RequirementSource> result, String file, String parent, String excerpt) {
        result.add(new RequirementSource(file == null ? "NOTICE_BODY" : "ATTACHMENT", file,
                MARKER + " " + parent.strip(), null, excerpt));
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
}
