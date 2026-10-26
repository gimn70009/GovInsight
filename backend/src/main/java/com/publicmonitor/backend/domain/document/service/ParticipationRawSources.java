package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import java.util.regex.Pattern;

/** Preserve original sections and complete tables; the LLM decides which facts are conditions. */
final class ParticipationRawSources {
    private static final Pattern HEADING = Pattern.compile("^(?:[0-9]+[.)]|[가-하][.)]|[□■])\\s*.+");
    private static final Pattern FORM = Pattern.compile("^(?:[<\\[]\\s*)?(?:붙임|별첨|별지|서식|양식|작성예시|작성견본|참고자료|참고\\s*\\d+).*?");

    record ScopedSources(List<ParticipationRule> rules, ParticipationScopeIndex scopes) {}

    static List<ParticipationRule> collect(String body, List<DocumentAttachment> attachments) {
        return collectWithScopes(body, attachments).rules();
    }

    static ScopedSources collectWithScopes(String body, List<DocumentAttachment> attachments) {
        var current = collectWithScopes(body, attachments, false);
        current.scopes().compatibility(collectWithScopes(body, attachments, true));
        return current;
    }

    private static ScopedSources collectWithScopes(String body, List<DocumentAttachment> attachments, boolean frozen) {
        var result = new ArrayList<ParticipationRule>();
        var scopes = new ParticipationScopeIndex(frozen);
        for (String document : ParticipationCategorySources.documents(body))
            collectDocument(document, null, result, scopes, frozen);
        for (var file : attachments) if (file.getParseStatus() == AttachmentParseStatus.COMPLETED
                && ParticipationCategorySources.allowed(file.getFileName()))
            for (String document : ParticipationCategorySources.documents(file.getExtractedText()))
                collectDocument(document, file.getFileName(), result, scopes, frozen);
        return new ScopedSources(result, scopes);
    }

    private static void collectDocument(String document, String file, List<ParticipationRule> result, ParticipationScopeIndex scopes, boolean frozen) {
        int start = result.size();
        collect(document, file, result, frozen);
        scopes.addDocument(document, result.subList(start, result.size()));
    }

    private static void collect(String document, String file, List<ParticipationRule> result, boolean frozen) {
        String context = recruitmentContext(document);
        String heading = "";
        String numberedSection = "";
        int start = 0, offset = 0;
        boolean table = false;
        for (String line : document.split("(?<=\\n)", -1)) {
            String stripped = line.strip();
            if ((frozen ? FORM.matcher(stripped).matches() && !stripped.contains("공고")
                    : !table && formBoundary(stripped))) {
                add(document.substring(start, offset), file, heading, context, result);
                return;
            }
            boolean numbered = stripped.matches("^\\d{1,2}(?:-\\d{1,2})+[.]?\\h*[가-힣A-Za-z].*");
            boolean boundary = !table && stripped.length() <= 90
                    && (HEADING.matcher(stripped).matches() || (!frozen && numbered));
            if (boundary && offset > start) {
                add(document.substring(start, offset), file, heading, context, result);
                start = offset;
                if (!frozen && numbered) numberedSection = stripped;
                else if (!frozen && stripped.matches("^\\d+[.)].*")) numberedSection = "";
                heading = !frozen && stripped.matches("^[□■].*") && !numberedSection.isBlank()
                        ? numberedSection + " / " + stripped : stripped;
            }
            if (!frozen && boundary && offset == 0) {
                if (numbered) numberedSection = stripped;
                heading = stripped;
            }
            if (stripped.equals("[표]")) table = true;
            if (stripped.equals("[/표]")) table = false;
            offset += line.length();
        }
        add(document.substring(start), file, heading, context, result);
    }

    private static boolean formBoundary(String line) {
        // "양식교부 및 접수처" is a field in the notice, not a separate application form.
        if (line.contains("공고") || line.matches("^양식\\h*(?:교부|배부|제공|다운로드|및).*")) return false;
        return line.matches("^(?:[<\\[]\\h*)?(?:붙임|별첨|별지|서식|양식|작성예시|작성견본|참고자료|참고\\h*\\d+)"
                + "(?:$|[\\h\\d\\]>）):：.].*)");
    }

    private static String recruitmentContext(String document) {
        String introduction = document.substring(0, Math.min(1800, document.length()));
        var context = new ArrayList<String>();
        for (String line : introduction.split("\\R")) {
            String text = line.strip();
            if (text.length() > 240) continue;
            if (text.matches(".*(?:주관|운영|수행)\\s*기관.{0,45}모집.*")
                    || text.matches(".*창업기업.{0,60}별도.{0,10}공고.*")) context.add(text);
        }
        return context.stream().sorted(Comparator.comparing((String text) -> !text.contains("별도")))
                .limit(2).collect(java.util.stream.Collectors.joining(" / "));
    }

    private static void add(String excerpt, String file, String heading, String context, List<ParticipationRule> result) {
        if (excerpt.isBlank()) return;
        var source = new RequirementSource(file == null ? "NOTICE_BODY" : "ATTACHMENT", file, context.isBlank() ? heading : "[공고 대상] " + context + "\n[원문 구역] " + heading, null, excerpt);
        result.add(new ParticipationRule("EVIDENCE", "원문 조건", "", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(source)));
    }
}
