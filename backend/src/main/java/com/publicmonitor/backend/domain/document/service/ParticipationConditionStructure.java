package com.publicmonitor.backend.domain.document.service;

import java.util.*;
import java.util.regex.Pattern;

/** Read explicit cell and applicant boundaries before whitespace is flattened for display. */
final class ParticipationConditionStructure {
    private static final Pattern TARGET = Pattern.compile("(?:신청|지원|참여)?(?:대상|자격)");
    private static final Pattern ENTITY = Pattern.compile(".*(?:기업|기업자|기관|단체|소공인|소상공인|상점가|시장|상권활성화구역|협업체)(?:\\s*\\([^()]*\\))?$");
    private static final Pattern ROLE = Pattern.compile("^[-◦○ㅇ]?\\h*[（(](도입기업|공급기업|수요기업|실증기업|주관기업|참여기업|주관기관|공동기관)[)）]\\h*(.*)$");
    private static final Pattern ROLE_IN_QUOTE = Pattern.compile("[（(](도입기업|공급기업|수요기업|실증기업|주관기업|참여기업|주관기관|공동기관)[)）]|(?:^|[\\t\\r\\n])(도입기업|공급기업|수요기업|실증기업|주관기업|참여기업|주관기관|공동기관)\\h*[:：]");
    private static final Pattern LABEL = Pattern.compile("^(?:[□■◦○ㅇ]|[가-하][.)])?\\h*[(（]?(?:(?:신청|지원|참여)\\h*(?:대상|자격)|대상)[)）]?\\h*[:：]?\\h*");
    record TableTarget(String title, String quote) {}
    record RoleTarget(String audience, String value, String quote) {}

    static boolean entity(String value) { return ENTITY.matcher(value).matches(); }
    static boolean roleLine(String line) { return ROLE.matcher(line.strip()).matches(); }

    static boolean multipleTargets(String quote) {
        if (ROLE_IN_QUOTE.matcher(quote).results().count() > 1) return true;
        for (String line : quote.split("\\R")) {
            String[] cells = line.split("\\t", -1);
            int previous = -1;
            for (int column = 0; column < cells.length; column++) {
                String value = ParticipationLocalConditions.clean(cells[column]);
                if (value.length() < 8 || !entity(value)) continue;
                if (previous >= 0) {
                    String continuation = String.join(" ", Arrays.copyOfRange(cells, previous + 1, column + 1)).strip();
                    // PDF extraction may use tabs between ordinary words in one explicit AND/OR clause.
                    if (!continuation.matches("^(?:[（(]\\s*)?(?:또는|혹은|및|그리고)(?:\\s|[가-힣]).*")) return true;
                }
                previous = column;
            }
        }
        return false;
    }

    static List<TableTarget> tableTargets(String source) {
        var result = new ArrayList<TableTarget>();
        var pending = new ArrayList<TableTarget>();
        String[] lines = source.split("\\R");
        String[] header = null;
        boolean table = false, blocked = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String stripped = line.strip();
            if (stripped.equals("[표]")) {
                table = true; header = null; blocked = false; pending.clear(); continue;
            }
            if (stripped.equals("[/표]")) {
                if (!blocked && !followingQualifier(lines, index + 1)) result.addAll(pending);
                table = false; header = null; pending.clear(); continue;
            }
            if (!table || !line.contains("\t")) continue;
            String[] cells = line.split("\\t", -1);
            String label = cells[0].replaceAll("\\s+", "");
            if (label.matches(".*(?:제외|예외|필수|공통요건|신청조건|자격조건|추가요건).*")) blocked = true;
            if (label.matches("구분|유형|사업명|세부사업명")) {
                header = cells.length >= 3 && cells.length <= 12 ? cells : null;
                continue;
            }
            if (!TARGET.matcher(label).matches() || header == null) continue;
            if (header.length != cells.length) { blocked = true; continue; }
            var titles = new LinkedHashSet<String>();
            var row = new ArrayList<TableTarget>();
            for (int column = 1; column < cells.length; column++) {
                String title = header[column].strip().replaceFirst("^[①-⑳❶-❿]\\s*", "");
                String quote = cells[column].strip();
                String value = ParticipationLocalConditions.clean(quote.replace("⏎", " "));
                if (title.isBlank() || title.length() > 60 || title.contains("⏎") || !titles.add(title)
                        || quote.isBlank() || !entity(value) || value.length() > 240
                        || value.matches(".*(?:[※*]|아래|다음|상기|참조|확인\\s*필요).*")) {
                    row.clear(); blocked = true; break;
                }
                row.add(new TableTarget(title, quote));
            }
            pending.addAll(row);
        }
        return result;
    }

    private static boolean followingQualifier(String[] lines, int start) {
        StringBuilder note = new StringBuilder();
        for (int i = start; i < Math.min(lines.length, start + 7); i++) {
            String line = lines[i].strip();
            if (line.isBlank()) continue;
            if (note.isEmpty() && !line.matches("^(?:[※*]|단[,，]|다만).*")) break;
            if (line.matches("^(?:[□■]|\\[표\\]|\\d+[.)]).*")) break;
            note.append(line).append(' ');
        }
        return Pattern.compile("단[,，]|다만|제외|예외|필수|불가|제한|하여야|해야").matcher(note).find();
    }

    static List<RoleTarget> roleTargets(String source) {
        var result = new ArrayList<RoleTarget>();
        String[] lines = source.split("\\R", -1);
        int[] starts = new int[lines.length];
        var breaks = Pattern.compile("\\R").matcher(source);
        for (int i = 1; i < lines.length && breaks.find(); i++) starts[i] = breaks.end();
        boolean active = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            var label = LABEL.matcher(line);
            if (label.find()) {
                // A shared consortium obligation cannot be silently removed from each member's conditions.
                active = line.substring(label.end()).isBlank();
                continue;
            }
            if (line.matches("^(?:[□■]|\\[/?표\\]).*")) active = false;
            var role = ROLE.matcher(line);
            if (!active || !role.matches()) continue;
            int last = i;
            String value = role.group(2);
            while (last + 1 < lines.length) {
                String next = lines[last + 1].strip();
                if (next.isEmpty() || next.matches("^(?:[-◦○ㅇ□■※*]|\\[/?표\\]).*") || roleLine(next)) break;
                value += " " + lines[++last].strip();
            }
            String next = last + 1 < lines.length ? lines[last + 1].strip() : "";
            if (next.matches("^(?:[※*]\\s*)?(?:단|다만)[,，].*")
                    || next.matches("^[※*].*(?:제외|필수|미등록|해야|하여야).*")) continue;
            value = ParticipationLocalConditions.clean(value);
            if (!entity(value) || value.length() > 240 || multipleTargets(value)) continue;
            String quote = source.substring(starts[i], starts[last] + lines[last].length()).strip();
            if (quote.length() <= 600) result.add(new RoleTarget(role.group(1), value, quote));
            i = last;
        }
        return result;
    }
}
