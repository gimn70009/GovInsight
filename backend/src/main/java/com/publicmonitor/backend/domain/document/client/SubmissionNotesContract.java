package com.publicmonitor.backend.domain.document.client;

import java.util.List;

public final class SubmissionNotesContract {
    private SubmissionNotesContract() {}
    public record Source(String id, String text) {}
    public record Document(String id, String title, String sourceId, String excerpt, String stage, String appliesTo) {}
    public record Request(List<Source> sources, List<Document> documents, boolean incomplete) {}
    public record Note(String sourceId, String quote, String text, String scope,
                       String documentId, String scopeQuote, String appliesTo) {}
    public record Response(List<Note> notes, String status) {
        public static Response unavailable() { return new Response(List.of(), "UNAVAILABLE"); }
    }
}
