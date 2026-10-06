package com.publicmonitor.backend.domain.report;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Presentation only: preserve conditions and reject ambiguous flattened tables. */
final class ReportFactPresentation {
    private static final Pattern TABLE_COLUMNS = Pattern.compile(
            "(?:^|\\s)(부문|훈격|규모|수공기간|공적기간|포상대상|포상규모|인원|구분|합계|지원대상|지원규모|지원기간)(?=\\s|$)");
    private ReportFactPresentation() {}

    static boolean unreadableTable(String value) {
        String text = value.replaceAll("\\s+", " ");
        return TABLE_COLUMNS.matcher(text).results().map(match -> match.group(1)).distinct().count() >= 3
                || Pattern.compile("(?<!\\S)\\d+\\s+(?:계|합계|총계)\\s+\\d+(?!\\S)").matcher(text).find()
                || Pattern.compile("(?:주관|공동)기관자격").matcher(text).results().count() > 1;
    }

    static String sourceCheckMessage(String label) {
        String subject = switch (label) {
            case "제출 주체·대상" -> "신청 자격과 제외 조건";
            case "제출·의견 기한" -> "마감일과 접수 기준";
            case "제출처·방법" -> "접수처와 제출 방법";
            case "문의 담당" -> "담당 부서와 연락 방법";
            case "사업 요약" -> "사업 내용과 세부 조건";
            default -> label;
        };
        return "원문 표에서 " + subject + "을 확인해 주세요.";
    }

    static List<String> lines(String label, String value) {
        if (unreadableTable(value)) return List.of(sourceCheckMessage(label));
        String text = value.replaceAll("(?:^|\\s+)[ㅇ○●▪•□■◦▫◇▶▸]\\s+", "\n").strip();
        if (label.equals("제출 주체·대상")) {
            text = text.replaceFirst("^(?:[가-하][.)]\\s*)?(?:신청\\s*자격|신청\\s*대상|지원\\s*대상)(?=\\s|[:：]|$)\\s*[:：]?\\s*", "");
        }
        if (label.contains("기한")) text = text.replaceFirst("^표제\\s*[:：]\\s*", "");
        // Sentence endings only; preserve dates, decimal amounts, names and URLs.
        text = text.replaceAll("(?<=[다요]\\.)\\s+(?=[가-힣])", "\n");
        text = text.replace("공.사", "공·사");
        return Arrays.stream(text.split("\\n")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
