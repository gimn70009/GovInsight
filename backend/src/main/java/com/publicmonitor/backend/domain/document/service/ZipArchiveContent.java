package com.publicmonitor.backend.domain.document.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** ZIP text positions are also saved draft identifiers; never renumber existing positions. */
final class ZipArchiveContent {
    private static final Pattern PART = Pattern.compile("(?m)^\\[파일: ([^\\r\\n]+)\\]\\r?\\n");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Entry(String fileName, String status, Integer partIndex, String reason, String actualFormat) {
        Entry at(int index) { return new Entry(fileName, status, index, reason, actualFormat); }
    }
    record Part(String name, String text) {}
    record Content(String text, String entriesJson) {}

    static List<Entry> entries(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var entries = JSON.readValue(json, Entry[].class);
            if (entries == null || Arrays.stream(entries).anyMatch(entry -> entry == null
                    || entry.fileName() == null || entry.status() == null)) return List.of();
            return List.of(entries);
        } catch (JacksonException exception) {
            return List.of();
        }
    }

    static List<Part> parts(String text) {
        if (text == null) return List.of();
        var matches = PART.matcher(text).results().toList();
        var result = new ArrayList<Part>();
        for (int index = 0; index < matches.size(); index++) {
            var match = matches.get(index);
            int end = index + 1 < matches.size() ? matches.get(index + 1).start() : text.length();
            result.add(new Part(ZipEntryFileName.restoreLegacyKorean(match.group(1)),
                    text.substring(match.end(), end).strip()));
        }
        return result;
    }

    static Content reconcile(String previousText, String incomingText, String incomingJson) {
        var manifest = entries(incomingJson);
        var previous = parts(previousText);
        if (manifest.isEmpty() || previous.isEmpty()) return new Content(incomingText, incomingJson);
        var incoming = parts(incomingText);
        var aligned = new ArrayList<Part>();
        // Empty placeholders reserve old identifiers without reusing stale text for analysis.
        previous.forEach(part -> aligned.add(new Part(part.name(), "")));
        Set<Integer> used = new HashSet<>();
        Map<Integer, Integer> positions = new HashMap<>();
        // Reserve matching filenames across the whole archive before falling back to body matches.
        // Otherwise a newly recovered file could take the identifier of a later existing file.
        for (int phase = 0; phase < 3; phase++) {
            for (int index = 0; index < incoming.size(); index++) {
                Part part = incoming.get(index);
                if (part.text().isBlank() || positions.containsKey(index)) continue;
                int target = match(previous, part, used, phase != 2, phase != 1);
                if (target < 0) continue;
                aligned.set(target, part);
                used.add(target);
                positions.put(index, target);
            }
        }
        for (int index = 0; index < incoming.size(); index++) {
            Part part = incoming.get(index);
            if (part.text().isBlank() || positions.containsKey(index)) continue;
            positions.put(index, aligned.size());
            aligned.add(part);
        }
        var remapped = manifest.stream().map(entry -> entry.partIndex() != null
                && positions.containsKey(entry.partIndex()) ? entry.at(positions.get(entry.partIndex())) : entry).toList();
        String text = aligned.stream().map(part -> "[파일: " + part.name() + "]\n" + part.text())
                .collect(Collectors.joining("\n\n"));
        return new Content(text, JSON.writeValueAsString(remapped));
    }

    private static int match(List<Part> previous, Part incoming, Set<Integer> used, boolean name, boolean text) {
        for (int index = 0; index < previous.size(); index++) {
            Part candidate = previous.get(index);
            if (!used.contains(index) && (!name || candidate.name().equals(incoming.name()))
                    && (!text || !candidate.text().isBlank()
                    && normalized(candidate.text()).equals(normalized(incoming.text())))) return index;
        }
        return -1;
    }

    private static String normalized(String text) {
        return text.lines().map(String::strip).collect(Collectors.joining("\n")).strip();
    }
}
