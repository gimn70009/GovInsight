package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import java.util.regex.Pattern;

/** Lists within a cell, with explicit row scope and item-specific notes. No filename rules. */
final class GroupedSubmissionTable {
    private static final String DOCUMENT = "체크리스트|계획서|신청서|공적조서|동의서|확인서|증명서|증명원|확약서|서약서|추천서|제안서|보고서|재무제표|등록증|등기부등본|등본|초본|증빙서류|증빙자료|증빙서|이력서|공문|발표자료|계약서|협약서|검토서|신고증";
    private static final Pattern BULLET = Pattern.compile("(?:^|(?<=\\s)|(?<=⏎))(?:[-–—•▪◦●○·]|[①-⑳]|\\d{1,2}[.)])\\s*");
    private static final Pattern NEGATIVE = Pattern.compile("제출\\s*(?:불필요|면제|생략)|제출하지|미제출|대체|대신");
    private static final Pattern CONDITIONAL = Pattern.compile("해당|경우|한함|한하여|한해|필요\\s*시|기관만|기업만|과제만|또는|택\\s*1");
    private static final Pattern GROUP = Pattern.compile("공통|필수(?:제출)?|선택(?:제출)?|해당시(?:제출)?|.*(?:기관|기업|법인|개인|신청자|사업자|연구|과제|대상)(?:만)?(?:제출|의경우)?");
    private record Item(String title, String quote, String condition, String context) {}
    private GroupedSubmissionTable() {}

    static List<SubmissionDocument> read(String[] rows, String context, String after, String fileName) {
        if (rows.length < 2 || context.matches("(?s).*(?:작성\\s*예시|작성\\s*견본|양식\\s*목록|첨부\\s*목록|참고\\s*자료).*")) return null;
        var headers = Arrays.stream(rows[0].split("\\t", -1)).map(s -> s.replaceAll("\\s+", "")).toList();
        var names = new ArrayList<Integer>();
        var groups = new ArrayList<Integer>();
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).matches("서류명|(?:제출|신청|접수|구비)(?:서류|자료)(?:목록|명)?")) names.add(i);
            if (headers.get(i).matches("구분|대상|제출대상|제출조건|조건|필수여부|제출여부|제출구분|필수선택")) groups.add(i);
        }
        if (names.size() != 1 || groups.size() != 1 || names.getFirst().equals(groups.getFirst())) return null;
        int name = names.getFirst(), group = groups.getFirst();
        boolean listed = Arrays.stream(rows).skip(1).anyMatch(row -> items(row.split("\\t", -1)[name]).size() > 1);
        if (!listed) return null;
        if (!headers.get(name).startsWith("제출") && !context.matches("(?s).*(?:제출|신청|접수|구비)\\s*(?:서류|자료).*")) return null;
        if (Pattern.compile("^\\s*[*※][^\\n]*(?:면제|생략|미제출|대체|대신)").matcher(after).find()) return List.of();
        var result = new ArrayList<SubmissionDocument>();
        for (int r = 1; r < rows.length; r++) {
            String[] row = rows[r].split("\\t", -1);
            String target = row[group].strip(), compact = target.replaceAll("\\s+", "");
            if (!GROUP.matcher(compact).matches() || NEGATIVE.matcher(target).find()) continue;
            var extras = new ArrayList<String>();
            for (int c = 0; c < row.length; c++) if (c != name && c != group && !row[c].isBlank()) extras.add(row[c].strip());
            var shared = Pattern.compile("※([^⏎]+)").matcher(row[name]).results().map(m -> m.group(1).strip())
                    .filter(note -> note.matches("(?s).*(?:모두|공통|각각).*")).toList();
            for (var item : items(row[name])) {
                var conditions = new LinkedHashSet<String>();
                if (!compact.matches("공통|필수|필수제출")) conditions.add(target);
                if (!item.condition().isBlank()) conditions.addAll(Arrays.asList(item.condition().split(" / ")));
                conditions.addAll(extras);
                conditions.addAll(shared);
                String condition = String.join(" / ", conditions);
                if (NEGATIVE.matcher(item.quote() + " " + condition).find() || item.quote().length() > 300 || condition.length() > 200) continue;
                String local = item.context() + " " + String.join(" ", extras);
                String stage = stage(local);
                if (stage == null) stage = stage(context);
                if (stage == null && Pattern.compile("신청|접수").matcher(context).find()) stage = "APPLICATION";
                // Unknown submission time remains visible but never becomes application-mandatory.
                String level = compact.startsWith("선택") ? "OPTIONAL"
                        : !compact.matches("공통|필수|필수제출") || CONDITIONAL.matcher(condition).find() ? "CONDITIONAL" : "MANDATORY";
                var source = new RequirementSource(fileName == null ? "NOTICE_BODY" : "ATTACHMENT", fileName,
                        "제출서류 표 내 목록", "표의 " + (r + 1) + "행", item.quote());
                result.add(SubmissionDocumentDisplay.prepare(new SubmissionDocument(
                        item.title(), item.quote(), level, stage, target, source)));
            }
        }
        return result;
    }

    private static List<Item> items(String cell) {
        var boundaries = new TreeSet<Integer>();
        boundaries.add(0); boundaries.add(cell.length());
        BULLET.matcher(cell).results().forEach(m -> boundaries.add(m.start()));
        Pattern.compile("⏎").matcher(cell).results().forEach(m -> boundaries.add(m.end()));
        var positions = new ArrayList<>(boundaries);
        var result = new ArrayList<Item>();
        String parent = "";
        for (int i = 1; i < positions.size(); i++) {
            String part = cell.substring(positions.get(i - 1), positions.get(i)).replaceAll("^[ ⏎]+|[ ⏎]+$", "");
            if (part.isBlank()) continue;
            if (!part.startsWith("·") && !part.startsWith("※")) parent = "";
            String[] noteParts = part.split("※", 2);
            String note = noteParts.length == 2 ? noteParts[1].strip() : "";
            String value = BULLET.matcher(noteParts[0]).replaceFirst("").strip();
            if (value.isBlank()) {
                if (!result.isEmpty() && !note.isBlank()) {
                    Item old = result.removeLast();
                    int start = cell.indexOf(old.quote()), end = cell.indexOf(part, start) + part.length();
                    result.add(new Item(old.title(), cell.substring(start, end),
                            old.condition().isBlank() ? note : old.condition() + " / " + note, old.context() + " " + note));
                }
                continue;
            }
            if (value.matches(".{1,70}(?:관련\\s*)?서류\\s*[(（][^()（）]+[)）]")) {
                parent = value;
                continue;
            }
            String title = Pattern.compile("또는|택\\s*1").matcher(value).find() ? value
                    : value.split("\\s+(?:원본|사본)(?=\\s|$)|\\s+(?:각\\s*)?\\d+\\s*(?:부|매)(?=\\W|$)|[:：]", 2)[0];
            title = title.replaceFirst("^[(（]해당\\s*시[)）]\\s*", "").strip().replaceFirst("\\s*[(（]해당\\s*시[)）]$", "");
            if (title.length() > 150 || !Pattern.compile("(?:" + DOCUMENT + ")(?=$|\\s|[(（])").matcher(title).find()) {
                if (!result.isEmpty() && !BULLET.matcher(part).lookingAt()) {
                    Item old = result.removeLast();
                    int from = cell.indexOf(old.quote()), to = cell.indexOf(part, from);
                    if (from >= 0 && to >= 0) result.add(new Item(old.title(), cell.substring(from, to + part.length()), old.condition(), old.context() + " " + part));
                    else result.add(old);
                }
                continue;
            }
            var conditions = new ArrayList<String>();
            if (!parent.isBlank()) conditions.add(parent);
            if (CONDITIONAL.matcher(value).find()) conditions.add(value);
            if (!note.isBlank()) conditions.add(note);
            result.add(new Item(title, part, String.join(" / ", conditions), value + " " + note));
            if (!part.startsWith("·")) parent = "";
        }
        return result;
    }

    private static String stage(String text) {
        if (Pattern.compile("(?:협약|계약)\\s*(?:시|시점|체결|단계|서류)").matcher(text).find()) return "AGREEMENT";
        if (Pattern.compile("선정\\s*(?:후|이후)").matcher(text).find()) return "POST_SELECTION";
        if (Pattern.compile("평가\\s*(?:시|단계|서류)").matcher(text).find()) return "EVALUATION";
        return null;
    }
}
