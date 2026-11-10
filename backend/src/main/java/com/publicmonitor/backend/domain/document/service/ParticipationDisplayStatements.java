package com.publicmonitor.backend.domain.document.service;

import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import java.util.*;
import java.util.regex.Pattern;

/** A display-only repair of explicit, readable prose; stored results and paid request identity stay intact. */
final class ParticipationDisplayStatements {
    private static final Pattern ITEM = Pattern.compile("(?m)^\\h*[◦○●•-]\\h*");
    private static final Pattern NOTE = Pattern.compile("(?m)^\\h*(?:[*※]+\\h*|-(?=\\h*(?:단[,，]|다만|예외|제외))\\h*)");
    private static final Pattern QUALIFICATION = Pattern.compile("^(?:사업)?신청(?:자격|요건)(?:및신청요건)?$");
    private static final Pattern INLINE_ELIGIBILITY = Pattern.compile(
            "^[\\h□■◦○●•-]*(?:신청|지원|참여|모집)\\h*(?:자격|대상|요건)\\h*[:：]\\h*");

    static List<Statement> prepare(ParticipationRewriteProjection.Input input, List<Statement> grounded) {
        var result = new LinkedHashMap<String, Statement>();
        for (var item : grounded) {
            String text = sentence(item.text()) ? item.text() : restore(item);
            if (text != null) add(result, new Statement(item.sourceIds(), item.criterion(), text, item.groupLabel(),
                    item.supportingQuotes(), item.kind(), item.scope(), item.audience(), item.details()));
        }
        var selected = ParticipationSourceSelection.select(input);
        for (var entry : input.evidence().entrySet()) for (var source : entry.getValue()) {
            if (!selected.includes(source) || !plainQualification(source)) continue;
            for (String quote : paragraphs(source)) {
                // Explicit model-owned explanations must not be restored as standalone legacy rows.
                if (grounded.stream().flatMap(item -> item.details().stream())
                        .filter(detail -> detail.sourceIds() != null && detail.sourceIds().contains(entry.getKey()))
                        .filter(detail -> detail.supportingQuotes() != null)
                        .flatMap(detail -> detail.supportingQuotes().stream())
                        .filter(Objects::nonNull).anyMatch(existing -> clause(existing).contains(clause(quote)))) continue;
                String first = quote.split("\\R", 2)[0].replaceFirst("^\\h*[◦○●•-]\\h*", "");
                String code = criterion(first);
                if (code == null) continue;
                String text = prose(quote);
                if (text == null) continue;
                String scope = input.scopes() == null ? null : input.scopes().displayScope(List.of(source), quote);
                if (coveredOrCompleted(result, entry.getKey(), code, scope, quote)) continue;
                add(result, new Statement(List.of(entry.getKey()), code, text, source.sectionTitle(),
                        List.of(quote), "ELIGIBILITY", scope, null));
            }
        }
        return List.copyOf(result.values());
    }

    private static boolean coveredOrCompleted(Map<String, Statement> result, String sourceId,
            String code, String scope, String quote) {
        var note = NOTE.matcher(quote);
        boolean hasNote = note.find();
        String main = hasNote ? quote.substring(0, note.start()) : quote;
        for (var entry : result.entrySet()) {
            var item = entry.getValue();
            if (!item.criterion().equals(code) || !item.sourceIds().contains(sourceId)) continue;
            if (item.supportingQuotes().stream().anyMatch(existing -> clause(existing).contains(clause(quote)))) return true;
            if (!"ELIGIBILITY".equals(item.kind()) || item.supportingQuotes().size() != 1
                    || !clause(item.supportingQuotes().getFirst()).equals(clause(main))) continue;
            // A punctuation-only match identifies the same main clause; never invent a second condition.
            if (hasNote && Objects.equals(item.scope(), scope) && item.audience() == null) {
                String tail = prose(quote.substring(note.start()));
                if (tail != null) {
                    String text = item.text().strip();
                    if (!compact(text).contains(compact(tail))) text += (text.matches("(?s).*[.!?]$") ? " " : ". ") + tail;
                    var quotes = new LinkedHashSet<>(item.supportingQuotes()); quotes.add(quote);
                    entry.setValue(new Statement(item.sourceIds(), item.criterion(), text, item.groupLabel(),
                            List.copyOf(quotes), item.kind(), item.scope(), item.audience(), item.details()));
                }
            }
            return true;
        }
        return false;
    }

    private static String clause(String value) { return compact(value.strip().replaceFirst("^[◦○●•-]\\h*", "")); }

    private static String restore(Statement item) {
        if (item.supportingQuotes().size() != 1 || !readable(item.text())) return null;
        String quote = item.supportingQuotes().getFirst();
        boolean inlineEligibility = "ELIGIBILITY".equals(item.kind()) && INLINE_ELIGIBILITY.matcher(quote).find();
        String value = item.text().strip();
        if (inlineEligibility) {
            // Remove only an explicit applicant heading; the quoted original stays untouched.
            quote = INLINE_ELIGIBILITY.matcher(quote).replaceFirst("");
            value = INLINE_ELIGIBILITY.matcher(value).replaceFirst("");
        }
        if (!readable(quote) || quote.contains("\t") || quote.contains("□") || quote.contains("■")
                || ITEM.matcher(quote).results().count() > 1 || NOTE.matcher(quote).find()) return null;
        value = value.replaceFirst("^[◦○●•-]\\h*", "").replaceAll("\\s+", " ");
        String original = quote.strip().replaceFirst("^[◦○●•-]\\h*", "").replaceAll("\\s+", " ");
        if (!compact(value).equals(compact(original))) return null;
        if (value.endsWith("우대")) return value + "합니다.";
        if (value.endsWith("가점")) return value + "을 부여합니다.";
        if ("PREFERENCE".equals(item.kind())) return null;
        String text = finish(value);
        if (!inlineEligibility && text != null && text.startsWith("신청 대상은 ")) return value + "이어야 합니다.";
        return text;
    }

    private static void add(Map<String, Statement> result, Statement item) {
        String key = item.criterion() + ":" + item.kind() + ":" + compact(item.text()) + ":" + Objects.toString(item.scope(), String.join(",", item.sourceIds())) + ":" + Objects.toString(item.audience(), "");
        var previous = result.get(key);
        if (previous == null) { result.put(key, item); return; }
        var ids = new LinkedHashSet<>(previous.sourceIds()); ids.addAll(item.sourceIds());
        var quotes = new LinkedHashSet<>(previous.supportingQuotes()); quotes.addAll(item.supportingQuotes());
        result.put(key, new Statement(List.copyOf(ids), previous.criterion(), previous.text(), previous.groupLabel(),
                List.copyOf(quotes), previous.kind(), previous.scope(), previous.audience(), java.util.stream.Stream.concat(previous.details().stream(), item.details().stream()).distinct().toList()));
    }

    static boolean fullyCovered(RequirementSource source, Collection<String> quotes) {
        String remaining = compact(source.excerpt());
        for (String quote : quotes) remaining = remaining.replace(compact(quote), "");
        String heading = source.sectionTitle();
        if (heading != null) {
            int original = heading.indexOf("[원문 구역] ");
            if (original >= 0) heading = heading.substring(original + "[원문 구역] ".length());
            remaining = remaining.replace(compact(heading), "");
        }
        return remaining.replaceAll("[□■◦○●•*※\\-:：]", "").isBlank();
    }

    private static boolean plainQualification(RequirementSource source) {
        String heading = Objects.toString(source.sectionTitle(), "");
        int original = heading.indexOf("[원문 구역] ");
        if (original >= 0) heading = heading.substring(original + "[원문 구역] ".length());
        heading = compact(heading).replaceFirst("^[□■]", "");
        return QUALIFICATION.matcher(heading).matches() && readable(source.excerpt())
                && !source.excerpt().contains("[표]") && !source.excerpt().contains("<")
                && source.excerpt().lines().filter(line -> line.strip().matches("[가-힣]{1,3}")).count() < 3;
    }

    private static List<String> paragraphs(RequirementSource source) {
        String raw = source.excerpt();
        var boundaries = ITEM.matcher(raw).results().filter(match ->
                !raw.substring(match.end()).matches("(?s)^(?:단[,，]|다만|예외|제외).*"))
                .map(match -> match.start()).toList();
        if (boundaries.isEmpty()) {
            String value = raw.strip();
            if (value.startsWith("□") || value.startsWith("■")) value = value.replaceFirst("^[^\\r\\n]*(?:\\R|$)", "").strip();
            return value.isEmpty() ? List.of() : List.of(value);
        }
        var result = new ArrayList<String>();
        for (int index = 0; index < boundaries.size(); index++) {
            String quote = raw.substring(boundaries.get(index), index + 1 < boundaries.size() ? boundaries.get(index + 1) : raw.length()).strip();
            if (!quote.contains("□") && !quote.contains("■") && !quote.contains("[")) result.add(quote);
        }
        return result;
    }

    private static String criterion(String first) {
        if (first.matches(".*(?:선정\\s*(?:규모|개수)|지원\\s*(?:기간|한도|비율|금액|규모)|정부\\s*지원|지원금|총사업비|우대|가점|우선\\s*선정).*")) return null;
        if (first.matches(".*(?:신용|부채비율|자본잠식).*")) return "CREDIT";
        if (first.matches("^[(-]?\\h*(?:회계\\s*처리|전담\\s*(?:조직|인력)|인력\\s*구성).*")) return "EXPERIENCE";
        var codes = ParticipationFocusCriterion.matching(first, "");
        if (!codes.isEmpty()) return codes.getFirst();
        return first.matches(".*(?:기업|기관|단체|사업자|소상공인|소공인|대학)$") ? "BUSINESS_FORM" : null;
    }

    private static String prose(String quote) {
        var pieces = new ArrayList<String>();
        int start = 0;
        var notes = NOTE.matcher(quote);
        while (notes.find()) {
            if (notes.start() > start) pieces.add(quote.substring(start, notes.start()));
            start = notes.end();
        }
        pieces.add(quote.substring(start));
        var sentences = new ArrayList<String>();
        for (String piece : pieces) {
            String value = piece.strip().replaceFirst("^[◦○●•-]\\h*", "").replaceAll("\\s+", " ")
                    .replaceAll("(?<=[가-힣A-Za-z])\\*+", "")
                    .replaceFirst("^\\(([^()]*)\\)\\h*", "$1: ");
            String formatted = finish(value);
            if (formatted == null) return null;
            sentences.add(formatted);
        }
        return String.join(" ", sentences);
    }

    private static String finish(String value) {
        if (value.isBlank() || !readable(value)) return null;
        if (sentence(value)) return value;
        value = value.replaceAll("(?<=[0-9A-Za-z])(?=이상|이하|미만|초과)", " ");
        if (value.matches(".*(?:이상|이하|미만|초과)\\s*신청 가능$"))
            return value.replaceFirst("\\s*신청 가능$", "인 경우 신청할 수 있습니다.");
        if (value.endsWith("가능 기관")) return value.replaceFirst("가능 기관$", "가능한 기관이어야 합니다.");
        if (value.endsWith("수 있음")) return value.replaceFirst("수 있음$", "수 있습니다.");
        if (value.endsWith("신청 가능")) return value + "합니다.";
        if (value.endsWith("인정")) return value + "됩니다.";
        if (value.endsWith("의미")) return value + "합니다.";
        if (value.endsWith("수행")) return value + "합니다.";
        if (value.endsWith("제외")) return value + "됩니다.";
        if (value.endsWith("함")) return value.substring(0, value.length() - 1) + "합니다.";
        if (value.matches(".*(?:원칙|필수|인 경우)$")) return value + "입니다.";
        if (value.matches(".*(?:이상|이하|미만|초과|이내)$")) return value + "이어야 합니다.";
        if (!value.matches(".*(?:제외|불가|다만|예외).*") && value.matches(".*(?:기업|기관|단체|사업자|소상공인|소공인)$"))
            return "신청 대상은 " + value + "입니다.";
        return null;
    }

    private static boolean sentence(String text) { return text != null && text.strip().matches("(?s).*(?:다|세요|해요|돼요|어요|아요|예요)[.!?]?"); }
    private static boolean readable(String text) {
        return text != null && !text.contains("((") && !text.contains("))") && !text.contains("[표]")
                && !text.contains("[/표]") && !text.matches("(?s).*[가-힣]{35,}.*") && !text.matches("(?s).*\\*{3,}.*");
    }
    private static String compact(String text) { return text.replaceAll("\\s+", ""); }
}
