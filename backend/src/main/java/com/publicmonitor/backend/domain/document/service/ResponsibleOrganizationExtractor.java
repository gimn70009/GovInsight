package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ResponsibleOrganization;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Read explicit role/name relations from the current source; never infer them from generated analysis. */
final class ResponsibleOrganizationExtractor {
    private static final int MAX_TEXT_LENGTH = 200_000;
    private static final String ROLES = "주관기관|주최기관|총괄기관|총괄부처|소관부처|시행부처|주관부처|시행기관|전담기관|운영기관|관리기관|전문기관|주관|주최|접수처|문의처";
    private static final String SPACED_ROLES = String.join("|", List.of(ROLES.split("\\|")).stream()
            .map(role -> String.join("\\h*", role.split(""))).toList());
    private static final String LEADING = "[\\h|□◦○ㅇ▪▫※*·•∙⦁■▶◈-]*(?:\\d+[.)]\\h*)?(?:(?:본\\h*)?사업\\h*(?:담당\\h*)?)?";
    private static final Pattern LABELED = Pattern.compile("^" + LEADING + "[(\\[【]?\\h*(?<role>" + SPACED_ROLES
            + ")(?:\\h*명)?(?=$|[\\h)\\]】:：|=(–—-]|은|는)\\h*"
            + "(?:[)\\]】]\\h*)?(?:[:：|=–—-]\\h*|\\(\\h*|(?:은|는)\\h*|$|\\h)(?<value>.*)$");
    private static final Pattern INLINE_ROLE = Pattern.compile("\\h*[,;/|]\\h*(?=[(\\[【]?(?:" + SPACED_ROLES + ")(?=$|[\\h:：()\\]】]))");
    private static final Pattern COMBINED = Pattern.compile("^" + LEADING + "[(\\[【]?\\h*(?<roles>(?:" + SPACED_ROLES
            + ")(?:\\h*/\\h*(?:" + SPACED_ROLES + "))+)\\h*[)\\]】]?\\h*[:：]?\\h+(?<value>.+)$");
    private static final Pattern REVERSED = Pattern.compile("^(.+?)\\(\\h*(" + SPACED_ROLES + ")\\h*\\)[.]?$");
    private static final Pattern SUBJECT = Pattern.compile("^(.+?)(?:이|가)\\h+(?:(?:본\\h*)?사업을\\h+)?(주관|주최|총괄|시행|운영)(?:합니다|한다|함)[.]?$");
    private static final Pattern FORM_HEADING = Pattern.compile("^[\\h\\[<(【]*(?:서식\\h*(?:제\\h*)?\\d|"
            + "(?:붙임|별첨|별지)\\h*(?:제\\h*)?\\d.{0,30}(?:신청서|계획서|동의서|서식|양식)|"
            + "(?:(?:사업|연구개발)계획서|신청서|공적조서|동의서)(?:\\h*양식)?[\\h)\\]】>]*$)");
    private static final Pattern EXAMPLE_HEADING = Pattern.compile("^.{0,12}(?:작성\\h*예시|작성\\h*예제|작성\\h*견본)");
    private static final Pattern NON_NOTICE_FILE = Pattern.compile("(?i)예시|견본|FAQ|참고자료|신청서|계획서|동의서|서식|양식|협약서|확인서|증명서|인정서|서약서");
    private static final Pattern NOTICE_FILE = Pattern.compile("공고|모집|안내");
    private static final Pattern ARCHIVE_ENTRY = Pattern.compile("(?m)^\\[파일: ([^\\r\\n]+)]\\h*$");
    private static final Pattern OTHER_CONTEXT = Pattern.compile("전년도|지난해|타\\h*사업|다른\\h*사업|참고\\h*사업|작성\\h*예시|예\\h*[:：]");
    private static final Pattern LINES = Pattern.compile("(.*?)(\\R|$)", Pattern.DOTALL);

    private ResponsibleOrganizationExtractor() {}

    static List<ResponsibleOrganization> extract(String body, List<DocumentAttachment> attachments) {
        var found = new LinkedHashMap<String, ResponsibleOrganization>();
        collect(body, null, found);
        for (var attachment : attachments) {
            String fileName = attachment.getFileName();
            if (attachment.getParseStatus() != AttachmentParseStatus.COMPLETED
                    || isNonNoticeFile(fileName)) continue;
            collect(attachment.getExtractedText(), attachment, found);
        }
        // A contact entry need not repeat an institution already identified with its operating role.
        var operatingNames = found.values().stream().filter(item -> !isContact(item.role()))
                .map(item -> nameKey(item.name())).toList();
        return found.values().stream()
                .filter(item -> !isContact(item.role()) || !operatingNames.contains(nameKey(item.name())))
                .sorted(Comparator.comparingInt(item -> isContact(item.role()) ? 1 : 0))
                .toList();
    }

    private static void collect(String text, DocumentAttachment attachment,
                                LinkedHashMap<String, ResponsibleOrganization> found) {
        if (text == null || text.isBlank()) return;
        String bounded = text.substring(0, Math.min(text.length(), MAX_TEXT_LENGTH));
        var entry = ARCHIVE_ENTRY.matcher(bounded);
        if (!entry.find()) {
            collectDocument(bounded, attachment, found);
            return;
        }
        // ZIP members retain their own filename and context: a form is not a notice,
        // and a role at one file's end must never use a name from the next file.
        String fileName = entry.group(1);
        int start = entry.end();
        while (true) {
            boolean hasNext = entry.find();
            int end = hasNext ? entry.start() : bounded.length();
            if (!isNonNoticeFile(fileName)) collectDocument(bounded.substring(start, end), attachment, found);
            if (!hasNext) break;
            fileName = entry.group(1);
            start = entry.end();
        }
    }

    private static boolean isNonNoticeFile(String fileName) {
        return NON_NOTICE_FILE.matcher(fileName).find() && !NOTICE_FILE.matcher(fileName).find();
    }

    private record Line(String text, int start, int end) {}
    private record Label(String role, String value) {}
    private record Cell(String text, int start, int end) {}
    private record RoleEvidence(String role, String value, String excerpt) {}

    private static List<Line> lines(String text) {
        var result = new ArrayList<Line>();
        var matcher = LINES.matcher(text);
        while (matcher.find() && matcher.end() > matcher.start()) {
            result.add(new Line(matcher.group(1), matcher.start(), matcher.end(1)));
        }
        return result;
    }

    private static void collectDocument(String text, DocumentAttachment attachment,
                                        LinkedHashMap<String, ResponsibleOrganization> found) {
        var lines = lines(text);
        int tableEnd = -1;
        String tableHeader = "";
        boolean form = false;
        boolean example = false;
        for (int index = 0; index < lines.size(); index++) {
            var source = lines.get(index);
            String line = source.text().strip();
            if (line.isBlank()) continue;
            if (line.equals("[표]")) {
                tableEnd = tableEnd(lines, index);
                if (tableEnd < 0) break;
                if (!validTable(lines, index, tableEnd)) {
                    index = tableEnd;
                    tableEnd = -1;
                } else {
                    int first = nextNonblank(lines, index, tableEnd);
                    tableHeader = lines.get(first).text();
                }
                continue;
            }
            if (index == tableEnd) {
                tableEnd = -1;
                continue;
            }
            // A description cell mentioning 신청서류 is not a form title for this or subsequent rows.
            var distinctCells = cells(line).stream().map(Cell::text).filter(cell -> !cell.isBlank()).distinct().toList();
            if (distinctCells.size() == 1 && FORM_HEADING.matcher(distinctCells.getFirst()).find()) form = true;
            if (distinctCells.size() == 1 && EXAMPLE_HEADING.matcher(distinctCells.getFirst()).find()) example = true;
            if (example) continue;
            String context = tableEnd >= 0 ? distinctCells.getFirst() : line;
            if (OTHER_CONTEXT.matcher(context).find()) continue;
            if (tableEnd >= 0) {
                int next = nextNonblank(lines, index, tableEnd);
                String following = next < tableEnd ? lines.get(next).text() : null;
                String gap = next < tableEnd ? text.substring(source.end(), lines.get(next).start()) : "";
                for (var evidence : tableRow(source.text(), following, gap, tableHeader)) {
                    if (!form || isContact(evidence.role())) add(evidence, attachment, found);
                }
                continue;
            }
            var evidence = labeledParts(line);
            if (evidence.size() == 1) {
                evidence = List.of(withContinuation(evidence.getFirst(), text, lines, index));
            }
            for (var item : evidence) if (!form || isContact(item.role())) add(item, attachment, found);
        }
    }

    private static RoleEvidence withContinuation(RoleEvidence evidence, String text, List<Line> lines, int index) {
        String value = evidence.value();
        int last = index;
        if (value.isBlank()) {
            int next = nextNonblank(lines, index, Math.min(lines.size(), index + 5));
            if (next < lines.size() && next < index + 5 && !lines.get(next).text().strip().startsWith("[")) {
                value = lines.get(next).text().strip();
                last = next;
            }
        } else if (ResponsibleOrganizationNames.read(value).isEmpty() && index + 1 < lines.size()
                && value.matches("[가-힣A-Za-z]{2,40}") && lines.get(index + 1).text().strip().matches("[가-힣A-Za-z]{2,20}")) {
            String joined = value + lines.get(index + 1).text().strip();
            if (ResponsibleOrganizationNames.complete(joined)) {
                value = joined;
                last = index + 1;
            }
        }
        if (ResponsibleOrganizationNames.complete(value)) {
            // Only complete adjacent names continue a list; headings and prose end it.
            int continuationEnd = Math.min(lines.size(), last + 8);
            for (int next = last + 1; next < continuationEnd; next++) {
                String candidate = lines.get(next).text().strip();
                if (!ResponsibleOrganizationNames.complete(candidate)) break;
                value += ", " + candidate;
                last = next;
                if (ResponsibleOrganizationNames.read(value).size() >= 8) break;
            }
        }
        String excerpt = last == index ? evidence.excerpt()
                : text.substring(lines.get(index).start(), lines.get(last).end()).strip();
        return new RoleEvidence(evidence.role(), value, excerpt);
    }

    private static int nextNonblank(List<Line> lines, int index, int end) {
        int next = index + 1;
        while (next < end && lines.get(next).text().isBlank()) next++;
        return next;
    }

    private static Label label(String value) {
        var matcher = LABELED.matcher(ResponsibleOrganizationNames.normalize(value).strip());
        if (!matcher.matches()) return null;
        return new Label(matcher.group("role").replaceAll("\\h", ""), matcher.group("value").strip());
    }

    private static List<RoleEvidence> labeledParts(String raw) {
        var combined = COMBINED.matcher(ResponsibleOrganizationNames.normalize(raw).strip());
        if (combined.matches()) {
            String[] roles = combined.group("roles").split("/");
            String[] values = combined.group("value").split("/", -1);
            // 주최/주관 does not assign every following institution to the last role.
            // Use aligned groups only; an ambiguous or incomplete mapping stays unconfirmed.
            if (roles.length != values.length) return List.of();
            var result = new ArrayList<RoleEvidence>();
            for (int index = 0; index < roles.length; index++) {
                if (ResponsibleOrganizationNames.read(values[index]).isEmpty()) return List.of();
                result.add(new RoleEvidence(roles[index].replaceAll("\\h", ""), values[index].strip(), raw.strip()));
            }
            return result;
        }
        var result = new ArrayList<RoleEvidence>();
        var delimiter = INLINE_ROLE.matcher(raw);
        int start = 0;
        while (delimiter.find()) {
            String part = raw.substring(start, delimiter.start()).strip();
            var previous = label(part);
            // A separator within 주최/주관 or an annotation cannot start a new role/value pair.
            if (previous == null || previous.value().isBlank() || unmatchedParenthesis(part)) continue;
            result.add(new RoleEvidence(previous.role(), previous.value(), part));
            start = delimiter.end();
        }
        String part = raw.substring(start).strip();
        var label = label(part);
        if (label != null) result.add(new RoleEvidence(label.role(), label.value(), part));
        else {
            String normalized = ResponsibleOrganizationNames.normalize(part);
            var reversed = REVERSED.matcher(normalized);
            var subject = SUBJECT.matcher(normalized);
            if (reversed.matches() && ResponsibleOrganizationNames.complete(reversed.group(1))) {
                result.add(new RoleEvidence(reversed.group(2).replaceAll("\\h", ""), reversed.group(1), part));
            } else if (subject.matches() && ResponsibleOrganizationNames.complete(subject.group(1))) {
                result.add(new RoleEvidence(subject.group(2) + "기관", subject.group(1), part));
            }
        }
        return result;
    }

    private static boolean unmatchedParenthesis(String value) {
        int depth = 0;
        for (char character : ResponsibleOrganizationNames.normalize(value).toCharArray()) {
            if (character == '(' || character == '[' || character == '【') depth++;
            if (character == ')' || character == ']' || character == '】') depth--;
        }
        return depth != 0;
    }

    private static void add(RoleEvidence evidence, DocumentAttachment attachment,
                            LinkedHashMap<String, ResponsibleOrganization> found) {
        for (String name : ResponsibleOrganizationNames.read(evidence.value())) {
            var item = new ResponsibleOrganization(name, evidence.role(),
                    attachment == null ? "NOTICE_BODY" : "ATTACHMENT",
                    attachment == null ? null : attachment.getFileName(), evidence.excerpt());
            found.putIfAbsent(evidence.role() + ":" + nameKey(name), item);
        }
    }

    private static List<Cell> cells(String row) {
        var result = new ArrayList<Cell>();
        int offset = 0;
        for (String raw : row.split("\t", -1)) {
            result.add(new Cell(raw.strip(), offset, offset + raw.length()));
            offset += raw.length() + 1;
        }
        return result;
    }

    private static List<RoleEvidence> tableRow(String row, String nextRow, String gap, String header) {
        var result = new ArrayList<RoleEvidence>();
        var cells = cells(row);
        var headers = cells(header);
        var below = nextRow == null ? List.<Cell>of() : cells(nextRow);
        for (int column = 0; column < cells.size(); column++) {
            var cell = cells.get(column);
            if (cell.text().isBlank() || (column > 0 && cell.text().equals(cells.get(column - 1).text()))) continue;
            var labels = labeledParts(cell.text());
            for (var entry : labels) {
                if (!entry.value().isEmpty()) {
                    result.add(entry);
                    continue;
                }
                // Reverse columns are used only when the table explicitly identifies a name column.
                int nameColumn = nameColumn(headers);
                if (nameColumn >= 0 && nameColumn < column && column < headers.size()
                        && headers.get(column).text().matches("역\\h*할|구\\h*분")
                        && !ResponsibleOrganizationNames.read(cells.get(nameColumn).text()).isEmpty()) {
                    result.add(new RoleEvidence(entry.role(), cells.get(nameColumn).text(),
                            row.substring(cells.get(nameColumn).start(), cell.end()).strip()));
                    continue;
                }
                int lastRole = column, right = column + 1;
                while (right < cells.size() && (cells.get(right).text().isBlank() || cells.get(right).text().equals(cell.text()))) {
                    if (!cells.get(right).text().isBlank()) lastRole = right;
                    right++;
                }
                if (right < cells.size() && !ResponsibleOrganizationNames.read(cells.get(right).text()).isEmpty()) {
                    result.add(new RoleEvidence(entry.role(), cells.get(right).text(),
                            row.substring(cells.get(lastRole).start(), cells.get(right).end()).strip()));
                } else if (below.size() == cells.size() && !ResponsibleOrganizationNames.read(below.get(column).text()).isEmpty()) {
                    result.add(new RoleEvidence(entry.role(), below.get(column).text(),
                            row.substring(cell.start()) + gap + nextRow.substring(0, below.get(column).end())));
                }
            }
        }
        return result;
    }

    private static int nameColumn(List<Cell> header) {
        int found = -1;
        for (int index = 0; index < header.size(); index++) {
            if (!header.get(index).text().replaceAll("\\h", "").matches("기관명|기관|담당기관")) continue;
            if (found >= 0) return -1;
            found = index;
        }
        return found;
    }

    private static int tableEnd(List<Line> lines, int start) {
        for (int index = start + 1; index < lines.size(); index++) {
            if (lines.get(index).text().strip().equals("[/표]")) return index;
            if (lines.get(index).text().strip().equals("[표]")) return -1;
        }
        return -1;
    }

    private static boolean validTable(List<Line> lines, int start, int end) {
        int first = nextNonblank(lines, start, end);
        if (first >= end || end - start > 10_001) return false;
        int width = lines.get(first).text().split("\t", -1).length;
        if (width < 2 || width > 256) return false;
        for (int index = first; index < end; index++) {
            String row = lines.get(index).text();
            if (!row.isBlank() && row.split("\t", -1).length != width) return false;
        }
        return true;
    }

    private static boolean isContact(String role) {
        return role.equals("문의처") || role.equals("접수처");
    }

    private static String nameKey(String name) {
        return name.replaceAll("[\\h·ㆍ‧∙]", "").toLowerCase(Locale.ROOT);
    }
}
