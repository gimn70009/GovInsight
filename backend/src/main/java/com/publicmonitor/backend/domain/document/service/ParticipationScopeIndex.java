package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import java.util.regex.Pattern;

/** Source positions for display and structural context in new extraction requests. */
final class ParticipationScopeIndex {
    private static final Pattern NUMBERED = Pattern.compile("^(\\d{1,2}(?:[-.]\\d{1,2})*)[.)]?\\s+(.+)$");
    private static final Pattern PUNCTUATED = Pattern.compile("^(\\d{1,2}(?:[-.]\\d{1,2})*)[.)]\\s*(.+)$");
    private static final Pattern JOINED_SUBSECTION = Pattern.compile("^(\\d{1,2}(?:-\\d{1,2})+)[.)]?\\s*([가-힣A-Za-z].+)$");
    private static final Pattern NUMBER_ONLY = Pattern.compile("^\\d{1,2}(?:-\\d{1,2})*[.)]?$");
    private static final Pattern ROMAN = Pattern.compile("^([ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+|[IVX]+)[.)]?\\s+(.+)$");
    private static final Pattern BULLET = Pattern.compile("^[□■]\\s*(.+)$");
    private static final Pattern CONTENT = Pattern.compile("^(?:[□■○◦●•ㅇ]|\\[표\\]).*");
    private static final Pattern TOC_ENTRY = Pattern.compile(".*(?:\\.{2,}|…|·{2,}|\\t\\s*\\d+\\s*$|\\s{2,}\\d+\\s*$).*");
    private final Map<RequirementSource, List<Span>> sources = new LinkedHashMap<>();

    private final Map<RequirementSource, String> contexts = new LinkedHashMap<>();

    record Line(String text, int from, int to) {}
    record Span(int from, int to, List<String> labels) {}
    record Heading(int level, String title) {}

    void addDocument(String document, List<ParticipationRule> rules) {
        var lines = lines(document);
        var stack = new TreeMap<Integer, String>();
        var spans = new ArrayList<Span>();
        boolean table = false;
        boolean contents = false;
        int skipTitle = -1;
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            String text = line.text().strip();
            if (text.startsWith("[파일:")) { stack.clear(); contents = false; table = false; }
            if (text.replaceAll("\\s+", "").matches("(?:목차|차례)")) {
                contents = true;
                stack.clear();
            }
            if (text.equals("[표]")) table = true;
            if (!table && i != skipTitle && !text.startsWith("[파일:")) {
                Heading heading = heading(text);
                if (heading == null && NUMBER_ONLY.matcher(text).matches() && i + 1 < lines.size()) {
                    String title = lines.get(i + 1).text().strip();
                    if (standaloneTitle(title) && hasContent(lines, i + 2)) {
                        heading = numbered(text.replaceFirst("[.)]$", ""), title);
                        skipTitle = i + 1;
                    }
                }
                boolean contentsPage = contents && text.matches(".*\\s\\d+\\s*$");
                if (contents && heading != null && !contentsPage && !TOC_ENTRY.matcher(text).matches() && hasContent(lines, i + 1))
                    contents = false;
                if (!contents && heading != null && !TOC_ENTRY.matcher(text).matches()) {
                    // Documents often restart section numbering inside each program.
                    int level = generic(heading.title()) ? 1000 : heading.level();
                    stack.tailMap(level, true).clear();
                    stack.put(level, generic(heading.title()) ? "" : heading.title());
                }
                if (!contents && heading == null && common(text)) stack.put(100, "공통");
                else if (!contents && heading == null && !TOC_ENTRY.matcher(text).matches() && standaloneTitle(text) && !generic(text)
                        && hasContent(lines, i + 1)) {
                    int level = text.matches(".*(?:형|트랙)$") ? 100 : 1;
                    stack.tailMap(level, true).clear();
                    stack.put(level, cleanTitle(text));
                }
            }
            spans.add(new Span(line.from(), line.to(), labels(stack)));
            if (text.equals("[/표]")) table = false;
        }
        int cursor = 0;
        for (var rule : rules) for (var source : rule.sources()) {
            String excerpt = source.excerpt();
            int start = document.indexOf(excerpt, cursor);
            if (start < 0) continue;
            String parent = parentSection(document.substring(0, start));
            if (secondaryBuyer(document, parent)) parent += "\n[별도 참여 역할] 구매기관";
            contexts.put(source, parent);
            int end = start + excerpt.length();
            var local = sources.computeIfAbsent(source, ignored -> new ArrayList<>());
            for (var span : spans) if (span.to() > start && span.from() < end)
                local.add(new Span(Math.max(0, span.from() - start), Math.min(excerpt.length(), span.to() - start), span.labels()));
            cursor = end;
        }
    }

    String context(List<RequirementSource> evidence) {
        return evidence.stream().map(contexts::get).filter(Objects::nonNull).filter(s -> !s.isBlank())
                .distinct().collect(java.util.stream.Collectors.joining(" / "));
    }

    private static boolean secondaryBuyer(String document, String parent) {
        if (!Pattern.compile("구매\\s*기관.*(?:현황|신청|참여)").matcher(parent).find()) return false;
        String introduction = document.substring(0, Math.min(document.length(), 600)).replaceAll("\\s+", "");
        if (introduction.matches(".*구매기관.{0,20}모집.*")) return false;
        return introduction.matches(".*(?:기업|제품).{0,60}(?:판로|납품).{0,30}지원.*");
    }

    private static String parentSection(String prefix) {
        String[] lines = prefix.split("\\R");
        String current = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.startsWith("[파일:")) current = "";
            String title = null;
            if (line.matches("[0-9]{1,2}") && i + 1 < lines.length) title = lines[i + 1].strip();
            else {
                var match = Pattern.compile("^[0-9]{1,2}[.)]?\\s+([가-힣].*)$").matcher(line);
                if (match.matches()) title = match.group(1).strip();
            }
            if (title != null && title.length() <= 60 && !title.matches(".*[0-9%:：].*")
                    && title.matches(".*(?:개요|자격|대상|안내|신청|요건|절차|내용|과제|프로그램|제외대상)$"))
                current = title.replaceAll("\\s+", " ");
        }
        return current;
    }

    String scope(List<RequirementSource> evidence, String quote) {
        var labels = new LinkedHashSet<String>();
        boolean unknown = false;
        for (var source : evidence) {
            var spans = sources.get(source);
            if (spans == null) { unknown = true; continue; }
            for (var match : quotePositions(source.excerpt(), quote)) {
                for (var span : spans) if (span.to() > match.from() && span.from() < match.to()) {
                    if (span.labels().isEmpty()) unknown = true;
                    else labels.add(String.join(" · ", span.labels()));
                }
            }
        }
        if (labels.isEmpty()) return null;
        if (unknown) labels.add("적용 범위 확인 필요");
        return String.join(" / ", labels);
    }

    private static List<Span> quotePositions(String source, String quote) {
        var normalized = new StringBuilder();
        var offsets = new ArrayList<Integer>();
        for (int i = 0; i < source.length(); i++) if (" \t\n\r\f\u000B".indexOf(source.charAt(i)) < 0) {
            normalized.append(source.charAt(i));
            offsets.add(i);
        }
        String target = quote.replaceAll("\\s+", "");
        var result = new ArrayList<Span>();
        if (target.isEmpty()) return result;
        for (int from = normalized.indexOf(target); from >= 0; from = normalized.indexOf(target, from + 1))
            result.add(new Span(offsets.get(from), offsets.get(from + target.length() - 1) + 1, List.of()));
        return result;
    }

    private static List<Line> lines(String document) {
        var result = new ArrayList<Line>();
        int offset = 0;
        for (String line : document.split("(?<=\\n)", -1)) {
            result.add(new Line(line, offset, offset + line.length()));
            offset += line.length();
        }
        return result;
    }

    private static Heading heading(String line) {
        var roman = ROMAN.matcher(line);
        if (roman.matches() && title(roman.group(2))) return new Heading(0, cleanTitle(roman.group(2)));
        var punctuated = PUNCTUATED.matcher(line);
        if (punctuated.matches() && standaloneTitle(punctuated.group(2))) return numbered(punctuated.group(1), punctuated.group(2));
        var numbered = NUMBERED.matcher(line);
        if (numbered.matches() && standaloneTitle(numbered.group(2))) return numbered(numbered.group(1), numbered.group(2));
        var joined = JOINED_SUBSECTION.matcher(line);
        if (joined.matches() && standaloneTitle(joined.group(2))) return numbered(joined.group(1), joined.group(2));
        var bullet = BULLET.matcher(line);
        if (bullet.matches() && standaloneTitle(bullet.group(1)) && !generic(bullet.group(1)))
            return new Heading(100, common(bullet.group(1)) ? "공통" : cleanTitle(bullet.group(1)));
        return null;
    }

    private static Heading numbered(String number, String title) {
        int level = number.split("[-.]").length;
        return new Heading(level, common(title) ? "공통" : cleanTitle(title));
    }

    private static String cleanTitle(String value) { return value.split("\\t", 2)[0].strip(); }

    private static boolean title(String value) {
        String title = cleanTitle(value);
        return title.length() <= 70 && !title.matches(".*(?:기업|기관)$")
                && !title.matches("^(?:업력|매출액?|특허)\\s*[0-9].*") && title.matches(".*[가-힣A-Za-z].*") && !title.matches("^[\\[□■○◦●•*※].*")
                && !title.matches(".*(?:[:：]|합니다|됩니다|한다|한다\\.|이다|이상|이하|이내|미만|초과|기업은|기관은|신청가능|신청할|보유한|해당하는|\\.\\s*$).*");
    }

    private static boolean standaloneTitle(String value) {
        if (!title(value)) return false;
        String title = cleanTitle(value);
        return generic(title) || common(title)
                || title.matches(".*(?:자금|사업|프로그램|프로젝트|패키지|트랙|유형|부문|과정|형|지원|육성|조성)$")
                || title.matches(".*(?:형|트랙|유형)\\)$")
                || title.matches(".*[가-힣A-Za-z]\\s*\\d+\\+?$");
    }

    private static boolean hasContent(List<Line> lines, int start) {
        for (int i = start; i < Math.min(lines.size(), start + 7); i++) {
            String next = lines.get(i).text().strip();
            if (CONTENT.matcher(next).matches()) return true;
            var numbered = NUMBERED.matcher(next);
            if (numbered.matches() && !numbered.group(1).contains("-") && !numbered.group(1).contains(".")) return false;
            if (NUMBER_ONLY.matcher(next).matches() && !next.contains("-")) {
                if (i + 1 < lines.size() && generic(lines.get(i + 1).text().strip())) continue;
                return false;
            }
            if (next.matches(".*(?:신청|지원|참여).*(?:기업|대상|요건|자격).*")) return true;
        }
        return false;
    }

    static boolean generic(String title) {
        String value = title.replaceAll("\\s+", "").replaceAll("[:：]$", "");
        return value.matches("(?:사업|모집|지원|공고)?(?:개요|목적|내용|규모|기간|절차|일정|대상|대상및조건|조건|신청자격|신청요건|신청대상|추진목적|추진체계|유의사항|제출서류|평가기준|선정기준|문의처|문의|근거법령|관련법령|신청방법|접수방법)")
                || value.matches("(?:신청|지원|참여|모집)(?:자격|대상|요건|조건)|필수(?:요건|조건)|우대(?:사항|조건|항목)?|가점(?:사항|항목)?")
                || value.contains("운용요령") || value.contains("시행령") || value.contains("시행규칙");
    }

    private static boolean common(String title) {
        return title.replaceAll("\\s+", "").matches("(?:공통|공통사항|공통요건|공통신청자격|공통적용사항|전체공통|전체공통사항)");
    }

    private static List<String> labels(SortedMap<Integer, String> stack) {
        return stack.values().stream().filter(value -> !value.isBlank()).distinct().toList();
    }
}
