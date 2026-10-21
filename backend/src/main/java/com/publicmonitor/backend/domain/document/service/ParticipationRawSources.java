package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import java.util.regex.Pattern;

/** Preserve original sections and complete tables; the LLM decides which facts are conditions. */
final class ParticipationRawSources {
    private static final Pattern HEADING = Pattern.compile("^(?:[0-9]+[.)]|[가-하][.)]|[□■])\\s*.+");
    private static final Pattern FORM = Pattern.compile("^(?:[<\\[]\\s*)?(?:붙임|별첨|별지|서식|양식|작성예시|작성견본|참고자료|참고\\s*\\d+).*?");

    static List<ParticipationRule> collect(String body, List<DocumentAttachment> attachments) {
        var result = new ArrayList<ParticipationRule>();
        for (String document : ParticipationCategorySources.documents(body)) collect(document, null, result);
        for (var file : attachments) if (file.getParseStatus() == AttachmentParseStatus.COMPLETED
                && ParticipationCategorySources.allowed(file.getFileName()))
            for (String document : ParticipationCategorySources.documents(file.getExtractedText()))
                collect(document, file.getFileName(), result);
        return result;
    }

    private static void collect(String document, String file, List<ParticipationRule> result) {
        String heading = "";
        int start = 0, offset = 0;
        boolean table = false;
        for (String line : document.split("(?<=\\n)", -1)) {
            String stripped = line.strip();
            if (FORM.matcher(stripped).matches() && !stripped.contains("공고")) {
                add(document.substring(start, offset), file, heading, result);
                return;
            }
            boolean boundary = !table && stripped.length() <= 90 && HEADING.matcher(stripped).matches();
            if (boundary && offset > start) {
                add(document.substring(start, offset), file, heading, result);
                start = offset;
                heading = stripped;
            }
            if (stripped.equals("[표]")) table = true;
            if (stripped.equals("[/표]")) table = false;
            offset += line.length();
        }
        add(document.substring(start), file, heading, result);
    }

    private static void add(String excerpt, String file, String heading, List<ParticipationRule> result) {
        if (excerpt.isBlank()) return;
        var source = new RequirementSource(file == null ? "NOTICE_BODY" : "ATTACHMENT", file, heading, null, excerpt);
        result.add(new ParticipationRule("EVIDENCE", "원문 조건", "", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(source)));
    }
}
