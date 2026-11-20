package com.publicmonitor.backend.domain.document.service;

import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.Statement;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import java.util.*;
import java.util.regex.Pattern;

/** Verify program labels against the headings at the main quotation, including saved results. */
final class ParticipationProgramScope {
    private static final String REVIEW = "적용 범위 확인 필요";
    private static final Pattern TABLE = Pattern.compile("\\[표](.*?)\\[/표]", Pattern.DOTALL);
    private static final Set<String> PROGRAM_HEADERS = Set.of(
            "사업명", "세부사업명", "사업유형", "지원유형", "과제유형", "지원분야", "사업구분", "유형", "트랙");
    private static final Set<String> TARGET_HEADERS = Set.of("지원대상", "신청대상", "참여대상", "신청자격", "지원자격");

    private ParticipationProgramScope() {}

    static String resolve(String stored, Statement item, List<RequirementSource> sources, ParticipationScopeIndex index) {
        if (index == null || item.supportingQuotes().isEmpty() || review(stored)) return stored;
        var contexts = new LinkedHashSet<String>();
        boolean unknown = false;
        for (String quote : item.supportingQuotes()) {
            var cited = sources.stream().filter(source -> source.excerpt().contains(quote)).toList();
            String context = index.displayScope(cited, scopeQuote(quote));
            if (context == null || context.isBlank()) unknown = true;
            else if (ParticipationScopeIndex.administrativeHeading(context)) continue;
            else contexts.addAll(Arrays.asList(context.split(" / ")));
        }
        if (contexts.isEmpty()) return stored;
        // Keep an explicitly named table subtype; its parent heading is less specific.
        if (stored != null && tableProgram(stored, item, sources)) return stored;
        if (unknown || contexts.size() != 1 || contexts.stream().anyMatch(ParticipationProgramScope::review)) {
            // Never attach a condition to the first of several possible programs.
            return stored != null && contexts.stream().noneMatch(path -> related(path, stored)) ? REVIEW : stored;
        }
        String context = contexts.iterator().next();
        if (ParticipationScopeIndex.generic(context) || compact(context).matches(
                "(?:신청|지원|참여|모집)?(?:자격|요건)(?:및(?:신청|지원|참여)?(?:제외대상|제외사항|선정기준))?")) return stored;
        if (context.matches(".*(?:모집|모집공고|선정공고|신청안내)$")) return stored;
        // Common headings alone cannot disprove an explicit narrower type.
        if (context.equals("공통") && stored != null) return stored;
        return context;
    }

    private static String scopeQuote(String quote) {
        int end = quote.indexOf("[/표]");
        if (end < 0 || !quote.substring(0, end).contains("\t")) return quote;
        String tail = quote.substring(end + "[/표]".length()).strip();
        // Cached line ranges sometimes include the next section title after the last table row.
        // Limit scope lookup to that table; retain the complete original quotation for display.
        if (!tail.isBlank() && tail.lines().allMatch(line -> line.length() <= 80
                && line.matches("^[①-⑳❶-❿]\\s*[^\\t]+$"))) return quote.substring(0, end).stripTrailing();
        return quote;
    }

    private static boolean tableProgram(String stored, Statement item, List<RequirementSource> sources) {
        var allowed = new HashSet<String>();
        for (var source : sources) {
            var tables = TABLE.matcher(source.excerpt());
            while (tables.find()) {
                String table = tables.group(1);
                var quotes = item.supportingQuotes().stream().map(ParticipationProgramScope::scopeQuote).filter(table::contains).toList();
                if (quotes.isEmpty()) continue;
                var lines = table.lines().filter(line -> !line.isBlank()).toList();
                if (lines.size() < 2) continue;
                String[] header = lines.getFirst().split("\\t", -1);
                // Programs as columns: 구분 | 유형 A | 유형 B, followed by 지원대상.
                if (header.length > 1 && compact(header[0]).equals("구분")
                        && lines.stream().skip(1).anyMatch(line -> TARGET_HEADERS.contains(compact(line.split("\\t", -1)[0]))))
                    Arrays.stream(header).skip(1).map(ParticipationProgramScope::compact).forEach(allowed::add);
                // Programs as rows: 지원분야 | 지원대상. Only the cited row owns the subtype.
                for (int col = 0; col < header.length; col++) if (PROGRAM_HEADERS.contains(compact(header[col]))) {
                    for (String line : lines.subList(1, lines.size())) {
                        String[] cells = line.split("\\t", -1);
                        if (col < cells.length && quotes.stream().anyMatch(q -> line.contains(q) || q.contains(line)))
                            allowed.add(compact(cells[col]));
                    }
                }
            }
        }
        return Arrays.stream(stored.split(" / ")).allMatch(path -> {
            String[] parts = path.split(" · ");
            return allowed.contains(compact(parts[parts.length - 1]));
        });
    }

    private static boolean related(String context, String value) {
        return compact(context).equals(compact(value))
                || Arrays.stream(context.split(" · ")).anyMatch(part -> compact(part).equals(compact(value)));
    }
    private static boolean review(String value) {
        return value != null && value.matches(".*(?:확인 필요|미확인|확인필요).*");
    }
    private static String compact(String value) { return value.replaceAll("\\s+", ""); }
}
