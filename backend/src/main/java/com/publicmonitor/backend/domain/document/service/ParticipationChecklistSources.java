package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import java.util.*;
import java.util.regex.Pattern;

/** Only explicitly requested qualification checklists can cross the normal form boundary. */
final class ParticipationChecklistSources {
    static final String MARKER = "[자격 확인서]";
    static final String EXCEPTION = "[확인서 적용 예외]";
    private static final Pattern TITLE = Pattern.compile("(?:신청|지원|참여)\\h*자격\\h*(?:(?:적정성|적합성)\\h*)?(?:확인서|점검표|체크리스트)");
    private static final Pattern SECTION = Pattern.compile("(?m)^\\h*□[^\\r\\n]*");
    private static final Pattern END = Pattern.compile("(?m)^\\h*(?:위\\h*내용|20\\d{0,2}\\h*년|기관명|대표자|※\\h*모든\\h*신청기관).*");
    private static final Pattern EXEMPTION = Pattern.compile("(?m)^.*(?:산정\\s*(?:에서\\s*)?제외|적용\\s*(?:에서\\s*)?제외|면제|예외).*$");

    record Linked(RequirementSource source, String recruitment) {}

    static List<Linked> collect(String body, List<DocumentAttachment> attachments) {
        var notices = new ArrayList<RequirementSource>();
        if (body != null && !body.isBlank()) notices.add(new RequirementSource("NOTICE_BODY", null, "", null, body));
        for (var file : attachments) if (file.getParseStatus() == AttachmentParseStatus.COMPLETED
                && ParticipationCategorySources.allowed(file.getFileName()) && file.getExtractedText() != null)
            for (String doc : ParticipationCategorySources.documents(file.getExtractedText()))
                notices.add(new RequirementSource("ATTACHMENT", file.getFileName(), "", null, doc));
        var result = new ArrayList<Linked>();
        for (var file : attachments) {
            if (file.getParseStatus() != AttachmentParseStatus.COMPLETED || file.getExtractedText() == null) continue;
            // Inspect each embedded file separately; a ZIP name is not evidence of its contents.
            for (String document : file.getExtractedText().split("(?m)(?=^\\[파일:)")) {
                var title = TITLE.matcher(document.substring(0, Math.min(600, document.length())));
                if (!title.find()) continue;
                String name = title.group();
                var parents = notices.stream().filter(n -> !Objects.equals(n.attachmentName(), file.getFileName()))
                        .filter(n -> compact(n.excerpt()).contains(compact(name)))
                        .filter(n -> n.excerpt().matches("(?s).*(?:구비|제출|신청)\\s*서류.*")).toList();
                if (parents.isEmpty()) continue;
                var contexts = parents.stream().map(n -> ParticipationRecruitmentContext.from(n.excerpt()))
                        .filter(s -> !s.isBlank()).distinct().toList();
                // Different recruitment notices referring to the same generic form are ambiguous.
                if (contexts.size() > 1) continue;
                String recruitment = contexts.isEmpty() ? "" : contexts.getFirst();
                String content = document;
                int full = content.lastIndexOf("[문서 전체 텍스트 추출]");
                if (full >= 0) content = content.substring(full + "[문서 전체 텍스트 추출]".length());
                var headings = SECTION.matcher(content).results().toList();
                for (int i = 0; i < headings.size(); i++) {
                    int start = headings.get(i).start();
                    int stop = i + 1 < headings.size() ? headings.get(i + 1).start() : content.length();
                    var end = END.matcher(content).region(start, stop);
                    if (end.find()) stop = end.start();
                    String excerpt = content.substring(start, stop).strip();
                    if (!excerpt.contains("▸") && !excerpt.contains("?") && !excerpt.contains("여부")) continue;
                    result.add(new Linked(new RequirementSource("ATTACHMENT", file.getFileName(),
                            MARKER + " " + name + " / " + headings.get(i).group().strip(), null, excerpt), recruitment));
                }
                for (var parent : parents) {
                    // Preserve entire paragraphs, including wrapped subjects and exceptions.
                    for (String paragraph : paragraphs(parent.excerpt())) {
                        if (paragraph.length() > 1600 || !EXEMPTION.matcher(paragraph).find()) continue;
                        result.add(new Linked(new RequirementSource(parent.origin(), parent.attachmentName(),
                                EXCEPTION + " " + name, null, paragraph.strip()), recruitment));
                    }
                }
            }
        }
        var unique = new LinkedHashMap<String, Linked>();
        for (var item : result) unique.putIfAbsent(item.source().attachmentName() + ":" + item.source().sectionTitle()
                + ":" + compact(item.source().excerpt()), item);
        return List.copyOf(unique.values());
    }

    private static List<String> paragraphs(String document) {
        // Text extraction need not keep blank lines. Bullet and section starts delimit
        // complete wrapped clauses; splitting on every line would lose their subjects.
        return Arrays.asList(document.split("(?m)(?=^\\h*(?:[□■◦○●•ㅇ*※]|[가-하][.)]|[0-9]+[.)]|\\[(?:/?표|PDF|문서)))|\\R\\h*\\R"));
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
}
