package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.ScopeRegion;
import java.util.*;
import java.util.regex.Pattern;

/** Literal ownership of organization profiles and inline program branches, before excerpts are cut. */
final class ParticipationScopeOwnership {
    record Region(int start, int end, String scope) {}
    private record Line(String text, int start, int end) {}
    private static final Pattern NUMBER = Pattern.compile("^[0-9]{1,2}[.)]?$"),
            TITLE = Pattern.compile("^[0-9]{1,2}[.)]?\\s+(.+)$"),
            FIELD = Pattern.compile("\\[([^]\\n]+)]\\s*[-–·]\\s*\\[([^]\\n]+)]"),
            PROGRAM = Pattern.compile("^[\\h·•-]*[“‘\"「]([^”’\"」]{2,60}(?:사업|과제|프로그램|트랙|유형|형|공장))[”’\"」]\\s*(?:은|는|의|:|：)"),
            PROFILE = Pattern.compile("^(?:운영\\s*사업|지원\\s*사업|사업명)\\s*(?:\\t|[:：])\\s*(.+)$");
    private static final String META_END = "(?=상생기관명|주관기관명|운영기관명|공급기업|지원대상|문의연락처|연락처|$)";
    private static final Pattern FLAT_PROGRAM = Pattern.compile("^운영\\s*사업[\\s·:：]+(.+?)" + META_END),
            FLAT_ORGANIZER = Pattern.compile("(?:상생기관명|운영기관명|주관기관명)[\\s·:：]+(.+?)" + META_END);

    private ParticipationScopeOwnership() {}

    static List<Region> scan(String document) {
        var lines = new ArrayList<Line>();
        int offset = 0;
        for (String raw : document.split("(?<=\\n)", -1)) {
            lines.add(new Line(raw.strip(), offset, offset + raw.length()));
            offset += raw.length();
        }
        var profiles = new HashMap<Integer, String>();
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).text(), title = null;
            // Some PDF exports concatenate all profile cells into one physical line.
            var flatProgram = FLAT_PROGRAM.matcher(text);
            var flatOrganizer = FLAT_ORGANIZER.matcher(text);
            if (flatProgram.find() && flatOrganizer.find()) {
                String scope = (flatProgram.group(1).strip() + " · " + flatOrganizer.group(1).strip()).replaceAll("\\s+", " ");
                if (scope.codePointCount(0, scope.length()) <= 100) profiles.put(i, scope);
            }
            int after = i + 1;
            if (NUMBER.matcher(text).matches() && after < lines.size()) title = lines.get(after++).text();
            else {
                var match = TITLE.matcher(text);
                if (match.matches()) title = match.group(1).strip();
            }
            if (title == null || title.isBlank() || title.length() > 120) continue;
            String program = null, field = null;
            var inlineField = FIELD.matcher(title);
            if (inlineField.find()) {
                field = inlineField.group(1).strip() + " · " + inlineField.group(2).strip();
                title = title.substring(0, inlineField.start()).strip();
            }
            title = title.replaceAll("\\s+", " ");
            if (title.isBlank()) continue;
            for (int j = after; j < Math.min(after + 16, lines.size()); j++) {
                String next = lines.get(j).text();
                if (next.isBlank() || next.equals("[표]")) continue;
                var category = FIELD.matcher(next);
                if (category.matches()) {
                    field = category.group(1).strip() + " · " + category.group(2).strip();
                    continue;
                }
                var operating = PROFILE.matcher(next);
                if (operating.matches()) program = operating.group(1).replaceFirst("^[\\h·•-]+", "").replaceAll("\\s+", " ").strip();
                // A profile title must immediately introduce its metadata, not a later profile.
                break;
            }
            if (program != null) {
                String scope = (field == null ? program : field.replaceAll("\\s+", " ")) + " · " + title;
                if (scope.codePointCount(0, scope.length()) <= 100) profiles.put(i, scope);
            }
        }
        var result = new ArrayList<Region>();
        String profile = null, branch = null;
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            String text = line.text();
            if (text.startsWith("[파일:") || text.equals("[문서 전체 텍스트 추출]")) {
                profile = null; branch = null;
            }
            if (profiles.containsKey(i)) { profile = profiles.get(i); branch = null; }
            // Peer applicant bullets and section headings terminate subordinate program conditions.
            if (text.matches("^[□■◦○●ㅇ].*") || NUMBER.matcher(text).matches()) branch = null;
            var inline = PROGRAM.matcher(text);
            if (inline.find()) branch = inline.group(1).strip();
            String scope = branch == null ? profile : profile == null ? branch : profile + " · " + branch;
            if (scope == null || scope.codePointCount(0, scope.length()) > 100) continue;
            if (!result.isEmpty() && result.getLast().end() == line.start() && result.getLast().scope().equals(scope)) {
                var prior = result.removeLast();
                result.add(new Region(prior.start(), line.end(), scope));
            } else result.add(new Region(line.start(), line.end(), scope));
        }
        return result;
    }

    static List<ScopeRegion> local(List<Region> regions, String excerpt, int start) {
        var result = new ArrayList<ScopeRegion>();
        for (var region : regions) {
            int from = Math.max(start, region.start()) - start;
            int to = Math.min(start + excerpt.length(), region.end()) - start;
            if (from < to) result.add(new ScopeRegion(excerpt.codePointCount(0, from),
                    excerpt.codePointCount(0, to), region.scope()));
        }
        return result;
    }

    static Set<String> owners(String excerpt, List<ScopeRegion> regions, String quote) {
        var owners = new LinkedHashSet<String>();
        boolean unowned = false;
        if (quote.isBlank()) return owners;
        for (int at = excerpt.indexOf(quote); at >= 0; at = excerpt.indexOf(quote, at + 1)) {
            int start = excerpt.codePointCount(0, at), end = start + quote.codePointCount(0, quote.length());
            var overlapping = regions.stream().filter(r -> r.end() > start && r.start() < end).toList();
            if (overlapping.isEmpty()) unowned = true;
            overlapping.forEach(r -> owners.add(r.scope()));
            if (!overlapping.isEmpty() && overlapping.stream().noneMatch(r -> r.start() <= start && r.end() >= end))
                owners.add("적용 범위 확인 필요");
        }
        if (unowned && !owners.isEmpty()) owners.add("적용 범위 확인 필요");
        return owners;
    }
}
