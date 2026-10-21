package com.publicmonitor.backend.domain.document.service;

import java.util.*;
import java.util.regex.Pattern;

/** Reads only explicit tab-delimited columns. Missing cells or units are not reconstructed from digit order. */
final class ParticipationFundingTable {
    private static final Pattern VALUE = Pattern.compile("\\d[\\d,]*(?:[.]\\d+)?\\s*(?:억\\s*원|만\\s*원|천\\s*원|원|%|개사)\\s*(?:이내|이상|이하|미만|초과|내외)?");
    private ParticipationFundingTable() {}

    private static List<String> ratioSentences(List<String[]> rows) {
        String[] headers = rows.getFirst();
        if (headers.length != 2 || !headers[0].matches(".*(?:유형|구분).*")
                || !headers[1].matches(".*(?:자부담|현금|기관부담).*비율.*")) return List.of();
        var result = new ArrayList<String>();
        for (String[] cells : rows.subList(1, rows.size())) {
            if (cells.length != 2 || cells[0].isBlank() || !VALUE.matcher(cells[1].strip()).matches()) return List.of();
            result.add(cells[0].strip() + "의 " + headers[1].strip() + "은 " + cells[1].strip() + "입니다.");
        }
        return List.copyOf(result);
    }

    static List<String> sentences(String table) {
        var rows = table.lines().filter(line -> line.contains("\t")).map(line -> line.split("\\t", -1)).toList();
        if (rows.size() < 2) return List.of();
        int headerIndex = -1, national = -1, matching = -1, count = -1;
        for (int r = 0; r < Math.min(3, rows.size()); r++) {
            String[] cells = rows.get(r);
            for (int c = 0; c < cells.length; c++) {
                String header = cells[c].replaceAll("\\s+", "");
                if (header.matches("(?:국비|정부지원금)(?:\\(.*\\))?")) national = c;
                if (header.matches("(?:자부담|민간부담금)(?:\\(.*\\))?")) matching = c;
                if (header.matches("지원규모|규모")) count = c;
            }
            if (national >= 0 && matching >= 0) { headerIndex = r; break; }
        }
        if (headerIndex < 0) return ratioSentences(rows);
        var result = new ArrayList<String>();
        int width = rows.get(headerIndex).length;
        for (int r = headerIndex + 1; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            if (cells.length != width) return List.of();
            String subsidy = cells[national].strip(), contribution = cells[matching].strip();
            if (!VALUE.matcher(subsidy).matches() || !VALUE.matcher(contribution).matches()) return List.of();
            var typeCells = new ArrayList<String>();
            for (int c = 0; c < Math.min(national, matching); c++) {
                String header = rows.get(headerIndex)[c];
                if (header.matches(".*(?:유형|구분).*")) {
                    String type = cells[c].replaceAll("[*]+", "").replaceAll("\\s*[·ㆍ]\\s*", "").strip();
                    if (type.isEmpty()) return List.of();
                    typeCells.add(type);
                }
            }
            if (typeCells.isEmpty()) return List.of();
            String type = typeCells.size() == 1 ? typeCells.getFirst() : typeCells.getFirst() + "(" + String.join("·", typeCells.subList(1, typeCells.size())) + ")";
            String scale = count >= 0 && VALUE.matcher(cells[count].strip()).matches() ? "지원 규모는 " + cells[count].strip() + "이며, " : "";
            result.add(type + " 유형의 " + scale + "국비 지원한도는 " + subsidy + ", 자부담은 " + contribution + "입니다.");
        }
        return List.copyOf(result);
    }
}
