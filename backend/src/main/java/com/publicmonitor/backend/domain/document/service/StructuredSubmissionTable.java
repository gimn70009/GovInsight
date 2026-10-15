package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionDocument;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionGuide;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionNote;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Reads only explicitly bounded cells; blank requirement cells never inherit the previous row. */
final class StructuredSubmissionTable {
    private static final Pattern TABLE = Pattern.compile("(?ms)^\\[표\\]\\r?\\n(.*?)^\\[/표\\][^\\S\\n]*(?:\\n|$)");
    private static final Pattern CONDITION = Pattern.compile("해당|경우|한함|한하여|한해|면제|생략|미제출|대체|대신|택\\h*1");
    private StructuredSubmissionTable() {}

    static String withoutTables(String text) {
        return text.replaceAll("(?ms)^\\[표\\]\\r?\\n.*?(?:^\\[/표\\][^\\S\\n]*(?:\\n|$)|\\z)", "\n");
    }

    static SubmissionGuide read(String text, String fileName) {
        var result = new ArrayList<SubmissionDocument>();
        var notes = new ArrayList<SubmissionNote>();
        var matcher = TABLE.matcher(text);
        while (matcher.find()) {
            int memberStart = text.lastIndexOf("[파일:", matcher.start());
            if (memberStart >= 0) {
                int memberEnd = text.indexOf(']', memberStart);
                if (memberEnd < 0 || memberEnd >= matcher.start()) continue;
                String member = text.substring(memberStart + 4, memberEnd);
                if (member.matches("(?i).*(?:예시|견본|FAQ|참고자료|신청서|계획서|동의서|서식|양식|운영지침).*")
                        && !member.matches(".*(?:공고|모집|안내).*")) continue;
            }
            String context = text.substring(Math.max(Math.max(0, memberStart), matcher.start() - 600), matcher.start());
            // Do not borrow an obligation from a previous ZIP member or a reference/form section.
            int file = context.lastIndexOf("[파일:");
            if (file >= 0) context = context.substring(file);
            if (context.matches("(?s).*(?:작성\\h*예시|작성\\h*견본|첨부\\h*목록|양식\\h*목록|참고\\h*자료).*")) continue;
            String[] rawRows = matcher.group(1).split("\\R");
            if (rawRows.length < 2 || rawRows.length > 10_000) continue;
            int width = rawRows[0].split("\t", -1).length;
            if (width < 2 || width > 256 || Arrays.stream(rawRows).anyMatch(row -> row.split("\t", -1).length != width)) continue;
            int headerIndex = -1, nameColumn = -1;
            List<String> headers = List.of();
            for (int i = 0; i < Math.min(rawRows.length, 5); i++) {
                var candidate = Arrays.stream(rawRows[i].split("\t", -1)).map(StructuredSubmissionTable::key).toList();
                if (candidate.stream().filter(value -> !value.isBlank()).distinct().count() < 2) continue;
                int best = 0;
                boolean ambiguous = false;
                for (int column = 0; column < width; column++) {
                    if (!nameHeader(candidate.get(column))) continue;
                    int count = 0;
                    for (int r = i + 1; r < rawRows.length; r++) {
                        if (SubmissionDocumentResolver.isDocument(rawRows[r].split("\t", -1)[column])) count++;
                    }
                    if (count > best) { best = count; nameColumn = column; ambiguous = false; }
                    else if (count == best && count > 0) ambiguous = true;
                }
                if (best > 0 && !ambiguous) { headers = candidate; headerIndex = i; }
                if (headerIndex >= 0) break;
            }
            if (headerIndex < 0) continue;
            if (!headers.get(nameColumn).startsWith("제출") && !context.matches("(?s).*(?:제출|신청|구비)\\h*(?:서류|자료).*")) continue;
            String commonContext = context + "\n" + String.join("\n", Arrays.copyOf(rawRows, headerIndex));
            String commonStage = stage(commonContext);
            List<String> footnotes = footnotes(text.substring(matcher.end(), Math.min(text.length(), matcher.end() + 1_000)));
            int priorCount = result.size();
            for (int r = headerIndex + 1; r < rawRows.length; r++) {
                String[] cells = rawRows[r].split("\t", -1);
                String title = SubmissionDocumentResolver.cleanTitle(cells[nameColumn]);
                if (!SubmissionDocumentResolver.isDocument(title) || title.matches(".*(?:예시|참고용|제출서류\\h*없음).*")) continue;
                var instructions = new ArrayList<String>();
                var conditions = new ArrayList<String>();
                if (CONDITION.matcher(title).find()) conditions.add(SubmissionDocumentDisplay.readable(title));
                String marker = "", target = null, rowStage = commonStage;
                var included = new ArrayList<String>();
                var excluded = new ArrayList<String>();
                boolean roleColumns = false, incompleteRoles = false;
                for (int c = 0; c < width; c++) {
                    if (c == nameColumn) continue;
                    String header = headers.get(c), value = cells[c].strip();
                    if (header.matches("유형\\h*\\d+|주관(?:연구개발)?기관|공동(?:연구개발)?기관|영리기관|비영리기관|개인기업|법인기업")) {
                        roleColumns = true;
                        if (value.matches("[○◯O●◎✓√]")) included.add(header);
                        else if (value.matches("[Xx×✕✗-]")) excluded.add(header);
                        else incompleteRoles = true;
                        continue;
                    }
                    if (value.isBlank()) continue;
                    if (header.matches("순번|번호|연번")) continue;
                    if (header.matches("구분|필수여부|제출여부|제출구분|필수선택")) {
                        marker = value;
                    } else if (nameHeader(header)
                            && key(value).matches("필수|필수제출|해당시|해당시제출|선택|선택제출")) {
                        marker = value;
                    } else if (header.matches("제출시기|제출단계|시기")) {
                        rowStage = stage(value);
                    } else if (header.matches("제출대상|대상|제출조건|조건")) {
                        conditions.add(SubmissionDocumentDisplay.readable(value));
                        target = value;
                    } else if (CONDITION.matcher(value).find()) {
                        conditions.add(SubmissionDocumentDisplay.readable(value));
                    } else {
                        instructions.add(value);
                    }
                }
                // "필수"/"해당 시" can also appear alone in the remarks cell.
                if (marker.isBlank()) {
                    marker = instructions.stream().filter(value -> key(value).matches("필수|필수제출|해당시|해당시제출|선택|선택제출")).findFirst().orElse("");
                    if (!marker.isBlank()) {
                        String found = marker;
                        instructions.removeIf(value -> value.equals(found));
                    } else if (conditions.stream().anyMatch(value -> key(value).matches("해당시|해당시제출"))) marker = "해당 시";
                }
                String level = switch (key(marker)) {
                    case "필수", "필수제출" -> "MANDATORY";
                    case "선택", "선택제출" -> "OPTIONAL";
                    case "해당시", "해당시제출" -> "CONDITIONAL";
                    default -> null;
                };
                if (roleColumns) {
                    if (incompleteRoles) continue;
                    if (included.isEmpty()) continue;
                    target = String.join(" · ", included);
                    if (level == null) level = "MANDATORY";
                    if (!excluded.isEmpty()) {
                        conditions.add(String.join(" · ", excluded) + ": 제출 대상 아님");
                        if (!"OPTIONAL".equals(level)) level = "CONDITIONAL";
                    }
                }
                if (!conditions.isEmpty() && !"OPTIONAL".equals(level)) level = "CONDITIONAL";
                if ("CONDITIONAL".equals(level)) {
                    var recipients = instructions.stream().filter(value -> value.matches("[가-힣 ·]+(?:기관|기업|법인|개인|신청자|사업자|대상자)")).toList();
                    for (String recipient : recipients) {
                        target = recipient;
                        conditions.add("제출 대상: " + recipient);
                        instructions.remove(recipient);
                    }
                }
                boolean hasOwnNote = false;
                for (String note : footnotes) {
                    if (SubmissionNoteFormatter.common(note) || appliesTo(note, title)) {
                        hasOwnNote = true;
                        conditions.add(SubmissionDocumentDisplay.readable(note));
                        if (CONDITION.matcher(note).find() && !"OPTIONAL".equals(level)) level = "CONDITIONAL";
                    }
                }
                if ("CONDITIONAL".equals(level) && conditions.isEmpty()) conditions.add("원문에 ‘해당 시 제출’로 명시되어 있습니다. 구체적인 적용 대상은 원문을 확인해 주세요.");
                String quote = rawRows[r];
                if (hasOwnNote) {
                    // Keep an actual contiguous excerpt including the shared exception.
                    String last = footnotes.getLast();
                    int quoteEnd = text.indexOf(last, matcher.end()) + last.length();
                    quote = text.substring(matcher.start(), quoteEnd);
                    if (quote.length() > 12_000) continue;
                }
                var source = new RequirementSource(fileName == null ? "NOTICE_BODY" : "ATTACHMENT",
                        fileName, "제출서류 표", "표의 " + (r + 1) + "행", quote);
                result.add(new SubmissionDocument(title,
                        instructions.isEmpty() ? null : String.join(" ", instructions.stream().map(SubmissionDocumentDisplay::readable).toList()),
                        level, rowStage, target, source, SubmissionDocumentDisplay.names(title),
                        List.copyOf(conditions), SubmissionDocumentDisplay.formats(rawRows[r])));
            }
            if (result.size() > priorCount) {
                for (String note : footnotes) {
                    if (!SubmissionNoteFormatter.common(note) || !SubmissionNoteFormatter.actionable(note)) continue;
                    var source = new RequirementSource(fileName == null ? "NOTICE_BODY" : "ATTACHMENT",
                            fileName, "제출서류 표 공통 유의사항", null, note);
                    notes.add(new SubmissionNote(SubmissionNoteFormatter.format(note), null, source));
                }
            }
        }
        return new SubmissionGuide(List.copyOf(result), List.copyOf(notes));
    }

    private static boolean nameHeader(String value) {
        return value.matches("서류명|(?:제출|신청|접수|구비)(?:서류|자료)(?:목록|명)?");
    }

    private static boolean appliesTo(String note, String title) {
        String clean = SubmissionNoteFormatter.clean(note);
        return SubmissionDocumentDisplay.names(title).stream().anyMatch(name ->
                clean.startsWith(name) || clean.matches(".*" + Pattern.quote(name)
                        + "\\h*(?:제출\\h*)?(?:면제|생략|불필요).*"));
    }

    private static List<String> footnotes(String after) {
        var result = new ArrayList<String>();
        for (String line : after.split("\\R")) {
            if (line.isBlank()) continue;
            if (!line.strip().matches("[*※].*")) break;
            result.add(line.strip());
        }
        return result;
    }

    private static String stage(String text) {
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            if (line.matches(".*(?:협약|계약).*(?:시|체결|단계|서류).*")) return "AGREEMENT";
            if (line.matches(".*선정\\h*(?:후|이후).*")) return "POST_SELECTION";
            if (line.matches(".*평가\\h*(?:시|단계).*")) return "EVALUATION";
            if (line.matches(".*(?:신청|접수).*")) return "APPLICATION";
        }
        return null;
    }

    private static String key(String value) { return value.replaceAll("\\s+", ""); }
}
