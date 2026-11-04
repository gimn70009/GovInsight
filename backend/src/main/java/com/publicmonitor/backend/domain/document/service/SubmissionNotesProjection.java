package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.publicmonitor.backend.domain.document.client.SubmissionNotesContract.*;

/** Maps model IDs only to this version's original sources and document rows. */
final class SubmissionNotesProjection {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern MEASURE = Pattern.compile("\\d+(?:[.,]\\d+)*\\s*(?:개월|시간|천원|만원|억원|년|월|일|시|분|부|매|개|건|원|쪽|%|MB|KB|GB)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOUND = Pattern.compile("이상|이하|미만|초과|이내|이전|이후");
    private static final Pattern NEGATIVE = Pattern.compile("불가|불허|금지|않|없|면제|생략|제외|불필요|무효|미인정");
    private static final Pattern EXCEPTION = Pattern.compile("다만|경우|한하여|한해|해당|제외|면제|예외");
    record Origin(String id, String text, String fileName) {}
    record Input(Request request, Map<String, Origin> origins, Map<String, SubmissionDocument> documents) {}
    private SubmissionNotesProjection() {}

    static Input input(String body, List<DocumentAttachment> attachments, SubmissionGuide raw) {
        var origins = new LinkedHashMap<String, Origin>();
        boolean incomplete = add(origins, body, null);
        for (var file : attachments) {
            if (!SubmissionDocumentResolver.eligibleFile(file.getFileName())) continue;
            if (file.getParseStatus() != AttachmentParseStatus.COMPLETED) { incomplete = true; continue; }
            incomplete |= add(origins, file.getExtractedText(), file.getFileName());
        }
        var documents = new LinkedHashMap<String, SubmissionDocument>();
        var catalog = new ArrayList<com.publicmonitor.backend.domain.document.client.SubmissionNotesContract.Document>();
        for (var doc : raw.documents()) {
            if (catalog.size() >= 100) { incomplete = true; break; }
            if (doc.source() == null || doc.source().excerpt() == null || doc.source().excerpt().length() > 8000
                    || doc.title() == null || doc.title().length() > 1000) continue;
            var matches = origins.values().stream().filter(origin ->
                    (origin.fileName() == null ? "NOTICE_BODY" : "ATTACHMENT").equals(doc.source().origin())
                    && Objects.equals(origin.fileName(), doc.source().attachmentName())
                    && origin.text().contains(doc.source().excerpt())).toList();
            if (matches.size() != 1) continue;
            String id = "d" + catalog.size();
            catalog.add(new com.publicmonitor.backend.domain.document.client.SubmissionNotesContract.Document(
                    id, doc.title(), matches.getFirst().id(), doc.source().excerpt(), doc.stage(), doc.appliesTo()));
            documents.put(id, doc);
        }
        return new Input(new Request(origins.values().stream().map(s -> new Source(s.id(), s.text())).toList(),
                catalog, incomplete), origins, documents);
    }
    private static boolean add(Map<String, Origin> origins, String text, String fileName) {
        if (text == null || text.isBlank()) return fileName != null;
        int used = origins.values().stream().mapToInt(o -> o.text().length()).sum();
        int limit = Math.min(200_000, 600_000 - used);
        if (origins.size() >= 16 || limit <= 0) return true;
        String id = "s" + origins.size();
        origins.put(id, new Origin(id, text.substring(0, Math.min(limit, text.length())), fileName));
        return text.length() > limit;
    }
    static SubmissionGuide guide(SubmissionGuide raw, Input input, Response response) {
        if (response == null || response.notes() == null || response.notes().size() > 30 || response.status() == null
                || !List.of("READY", "PARTIAL", "UNAVAILABLE", "PENDING").contains(response.status()))
            return new SubmissionGuide(raw.documents(), List.of(), "UNAVAILABLE");
        if (List.of("PENDING", "UNAVAILABLE").contains(response.status()))
            return new SubmissionGuide(raw.documents(), List.of(), response.status());
        var notes = new LinkedHashSet<SubmissionNote>();
        boolean rejected = input.request().incomplete();
        for (var note : response.notes()) {
            if (!valid(note, input)) { rejected = true; continue; }
            var origin = input.origins().get(note.sourceId());
            var target = note.documentId() == null ? null : input.documents().get(note.documentId());
            String scope = "COMMON".equals(note.scope()) ? null : target == null ? note.appliesTo()
                    : target.title() + (target.appliesTo() == null || target.appliesTo().isBlank() ? "" : " · " + target.appliesTo());
            int start = Math.min(origin.text().indexOf(note.quote()), origin.text().indexOf(note.scopeQuote()));
            int end = Math.max(origin.text().indexOf(note.quote()) + note.quote().length(),
                    origin.text().indexOf(note.scopeQuote()) + note.scopeQuote().length());
            if (end - start > 8000) { rejected = true; continue; }
            var source = new RequirementSource(origin.fileName() == null ? "NOTICE_BODY" : "ATTACHMENT",
                    origin.fileName(), "제출 유의사항", target == null ? null : target.source().location(),
                    origin.text().substring(start, end));
            notes.add(new SubmissionNote(note.text(), scope, source));
        }
        return new SubmissionGuide(raw.documents(), List.copyOf(notes), rejected ? "PARTIAL" : response.status());
    }
    private static boolean valid(Note note, Input input) {
        if (note == null || note.sourceId() == null || note.text() == null || note.quote() == null
                || note.scopeQuote() == null || note.scope() == null) return false;
        var origin = input.origins().get(note.sourceId());
        if (origin == null || note.quote().isBlank() || note.quote().length() > 2000
                || note.scopeQuote().isBlank() || note.scopeQuote().length() > 2000
                || !origin.text().contains(note.quote()) || !origin.text().contains(note.scopeQuote())) return false;
        String text = note.text();
        if (text.isBlank() || text.length() > 800 || !text.equals(text.strip()) || !text.matches("(?s).*(?:다|요)\\.")
                || text.matches("(?s).*[\\p{Co}\\p{Cs}\\p{Cc}�⏎□■▣].*")
                || text.matches("(?s).*<[^>]+>.*") || text.matches("^[*#•※].*")) return false;
        if (!tokens(NUMBER, text).equals(tokens(NUMBER, note.quote()))
                || !tokens(MEASURE, text).equals(tokens(MEASURE, note.quote()))
                || !new HashSet<>(tokens(BOUND, text)).equals(new HashSet<>(tokens(BOUND, note.quote())))
                || NEGATIVE.matcher(text).find() != NEGATIVE.matcher(note.quote()).find()
                || (EXCEPTION.matcher(note.quote()).find() && !EXCEPTION.matcher(text).find())) return false;
        if ("DOCUMENT".equals(note.scope())) {
            var target = note.documentId() == null ? null : input.documents().get(note.documentId());
            var catalog = input.request().documents().stream().filter(d -> d.id().equals(note.documentId())).findFirst();
            var peers = input.request().documents().stream().filter(d -> catalog.isPresent()
                    && d.sourceId().equals(catalog.get().sourceId()) && d.title().equals(catalog.get().title())).toList();
            boolean distinctRow = peers.size() < 2 || (target != null
                    && note.scopeQuote().contains(target.source().excerpt())
                    && peers.stream().filter(d -> !d.id().equals(note.documentId()))
                            .noneMatch(d -> note.scopeQuote().contains(d.excerpt())));
            return distinctRow && target != null && note.appliesTo() == null && catalog.isPresent()
                    && catalog.get().sourceId().equals(note.sourceId())
                    && (note.scopeQuote().contains(target.title()) || note.scopeQuote().contains(target.source().excerpt()));
        }
        if (note.documentId() != null) return false;
        if ("COMMON".equals(note.scope())) return note.appliesTo() == null;
        return "UNRESOLVED".equals(note.scope()) && note.appliesTo() != null && !note.appliesTo().isBlank()
                && note.scopeQuote().contains(note.appliesTo());
    }
    private static List<String> tokens(Pattern pattern, String text) {
        return pattern.matcher(text).results().map(m -> m.group().replaceAll("[\\s,]", "").toLowerCase(Locale.ROOT)).sorted().toList();
    }
}
