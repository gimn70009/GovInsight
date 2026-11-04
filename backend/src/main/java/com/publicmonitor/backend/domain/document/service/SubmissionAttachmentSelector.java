package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Chooses one readable export of an exactly named document for submission guidance. */
final class SubmissionAttachmentSelector {
    // Structured/editable formats precede PDF; absence of any format is allowed.
    private static final List<String> FORMATS = List.of("hwpx", "hwp", "docx", "xlsx", "pdf");

    private SubmissionAttachmentSelector() {}

    static List<DocumentAttachment> select(List<DocumentAttachment> attachments,
                                           Predicate<DocumentAttachment> hasSubmissionDocuments) {
        var groups = new LinkedHashMap<String, List<DocumentAttachment>>();
        for (var file : attachments) {
            String name = file.getFileName(), format = format(name);
            String key = FORMATS.contains(format)
                    ? "document:" + name.substring(0, name.lastIndexOf('.')) : "other:" + groups.size();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(file);
        }
        var selected = new ArrayList<DocumentAttachment>();
        for (var group : groups.values()) {
            // Equal extensions alone do not establish an alternate export.
            if (group.stream().map(file -> format(file.getFileName())).distinct().count() < 2) {
                selected.addAll(group);
                continue;
            }
            var ordered = group.stream().sorted(Comparator.comparingInt(
                    file -> FORMATS.indexOf(format(file.getFileName())))).toList();
            var readable = ordered.stream().filter(SubmissionAttachmentSelector::readable).toList();
            selected.add(readable.stream().filter(hasSubmissionDocuments).findFirst()
                    // Keep a source for notes/saved evidence, or the failed source for incomplete status.
                    .orElseGet(() -> readable.isEmpty() ? ordered.getFirst() : readable.getFirst()));
        }
        return List.copyOf(selected);
    }

    private static boolean readable(DocumentAttachment file) {
        return file.getParseStatus() == AttachmentParseStatus.COMPLETED
                && file.getExtractedText() != null && !file.getExtractedText().isBlank();
    }

    private static String format(String name) {
        if (name == null || name.lastIndexOf('.') <= 0) return "";
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
