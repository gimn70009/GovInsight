package com.publicmonitor.backend.domain.report;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Resolve only explicit institution selection rows; incomplete rows remain unconfirmed. */
final class ReportApplicantPresentation {
    private static final Pattern ROLE = Pattern.compile(
            "주관\\s*기관\\s*외\\s*필수\\s*참여\\s*기관(?:\\s*유형)?"
            + "|(?:국내\\s*|해외\\s*|국외\\s*)?(?:주관|공동|참여)(?:\\s*연구\\s*개발)?\\s*기관(?:\\s*(?:유형|자격))?"
            + "|필수\\s*참여\\s*기관(?:\\s*유형)?");
    private static final Pattern CHECKBOX = Pattern.compile("[□☐■☑☒]");
    private static final Pattern OPTION = Pattern.compile(
            "(?:제한\\s*없음|기업|중소기업|중견기업|대기업|대학(?:교)?|출연(?:\\(연\\)|연)?|국공립(?:연)?|특정연"
            + "|기타\\s*비영리법인(?:\\(병원\\s*등\\))?|비영리법인|외국법인|연구기관|공공기관)"
            + "(?:\\s*[/·,]\\s*(?:대학|출연(?:\\(연\\)|연)?|국공립(?:연)?|특정연|중소기업|중견기업|대기업))*");
    private static final Pattern CONDITION = Pattern.compile("(?:^|\\s)(?=(?:단\\s*[,，:]|다만\\s*[,，:]?|※))");

    private ReportApplicantPresentation() {}

    /** Null means ordinary prose, for which the existing bullet formatter is appropriate. */
    static List<String> lines(String value) {
        if (!CHECKBOX.matcher(value).find()) return null;
        var roles = new ArrayList<java.util.regex.MatchResult>();
        boolean hasSelection = false;
        for (var role : ROLE.matcher(value).results().toList()) {
            String remainder = value.substring(role.end());
            boolean selection = remainder.matches("^\\s*[:：]?\\s*[□☐■☑☒][\\s\\S]*");
            int prior = roles.isEmpty() ? 0 : roles.getLast().end();
            boolean prose = remainder.matches("^\\s*[:：][\\s\\S]*")
                    && !CONDITION.matcher(value.substring(prior, role.start())).find();
            if (selection || prose) roles.add(role);
            hasSelection |= selection;
        }
        if (!hasSelection && !roles.isEmpty()) return null;
        if (roles.isEmpty()) {
            var boxes = CHECKBOX.matcher(value).results().toList();
            String firstOption = boxes.size() >= 2
                    ? value.substring(boxes.getFirst().end(), boxes.get(1).start()).strip() : "";
            boolean outline = Pattern.compile("^(?:신청\\s*자격|신청\\s*대상|지원\\s*대상)\\s*[:：]")
                    .matcher(firstOption).find();
            if (boxes.size() >= 2 && !outline) {
                var condition = CONDITION.matcher(value);
                String note = condition.find() ? "\n" + value.substring(condition.start()).strip() : "";
                return List.of("원문 확인 필요: 신청 기관의 역할과 선택 여부를 확인해 주세요." + note);
            }
            return null;
        }
        var result = new ArrayList<String>();
        String prefix = value.substring(0, roles.getFirst().start()).strip()
                .replaceFirst("^[□■]\\s*(?:신청\\s*자격|신청\\s*대상)\\s*[:：]?\\s*", "");
        if (ROLE.matcher(prefix).find()) {
            return List.of("원문 확인 필요: 신청 기관의 역할과 선택 여부를 확인해 주세요.");
        }
        if (!prefix.isBlank()) result.add(prefix);
        var seen = new HashSet<String>();
        for (int index = 0; index < roles.size(); index++) {
            var role = roles.get(index);
            String name = roleName(role.group());
            String row = value.substring(role.end(), index + 1 < roles.size() ? roles.get(index + 1).start() : value.length())
                    .replaceFirst("^\\s*[:：]?\\s*", "").strip();
            if (!seen.add(name)) {
                result.replaceAll(item -> item.startsWith(name + ":")
                        ? item.replaceFirst("^[^\\n]*", name + ": 원문 확인 필요") : item);
                String current = selected(row);
                if (current.contains("\n")) result.add(current.substring(current.indexOf('\n') + 1));
            } else {
                result.add(name + ": " + selected(row));
            }
        }
        return result;
    }

    private static String roleName(String raw) {
        String compact = raw.replaceAll("\\s+", "").replaceFirst("(?:유형|자격)$", "");
        if (compact.equals("주관기관외필수참여기관")) return "주관기관 외 필수 참여기관";
        if (compact.equals("필수참여기관")) return "필수 참여기관";
        return compact.replaceFirst("^(국내|해외|국외)", "$1 ");
    }

    private static String selected(String row) {
        var condition = CONDITION.matcher(row);
        String note = "";
        if (condition.find()) {
            note = row.substring(condition.start()).strip();
            row = row.substring(0, condition.start()).strip();
        }
        var boxes = CHECKBOX.matcher(row).results().toList();
        if (boxes.isEmpty() && !row.isBlank()) return note.isBlank() ? row : row + "\n" + note;
        // A solid-square-only list may be bullets, and an empty-square-only list has no selection.
        boolean checked = boxes.stream().anyMatch(box -> "■☑☒".contains(box.group()));
        boolean unchecked = boxes.stream().anyMatch(box -> "□☐".contains(box.group()));
        var choices = new ArrayList<String>();
        var options = new HashSet<String>();
        boolean valid = boxes.size() >= 2 && checked && unchecked && boxes.getFirst().start() == 0;
        for (int index = 0; index < boxes.size(); index++) {
            var box = boxes.get(index);
            String option = row.substring(box.end(), index + 1 < boxes.size() ? boxes.get(index + 1).start() : row.length())
                    .strip().replaceFirst("[;；|]+$", "").strip();
            if (!OPTION.matcher(option).matches() || !options.add(option.replaceAll("\\s+", ""))) valid = false;
            if ("■☑☒".contains(box.group())) choices.add(option.replaceAll("제한\\s*없음", "제한 없음"));
        }
        if (choices.size() > 1 && choices.contains("제한 없음")) valid = false;
        String resolved = valid ? String.join(", ", choices) : "원문 확인 필요";
        return note.isBlank() ? resolved : resolved + "\n" + note;
    }
}
