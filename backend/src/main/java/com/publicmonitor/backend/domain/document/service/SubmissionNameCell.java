package com.publicmonitor.backend.domain.document.service;

import java.util.Arrays;
import java.util.List;

/** The first paragraph names the document; explicit footnotes remain in its original cell. */
record SubmissionNameCell(String title, List<String> notes) {
    static SubmissionNameCell read(String value) {
        String[] parts = value.split("⏎", -1);
        String title = SubmissionDocumentResolver.cleanTitle(parts[0]);
        var notes = Arrays.stream(parts).skip(1).map(String::strip).filter(s -> !s.isBlank()).toList();
        if (!SubmissionDocumentResolver.isDocument(title)
                || notes.stream().anyMatch(s -> !s.matches("^[*※].*"))) return null;
        return new SubmissionNameCell(title, notes);
    }
}
