package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionDocument;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Separates readable names, instructions and conditions from the untouched evidence. */
final class SubmissionDocumentDisplay {
    private static final Pattern FORMAT = Pattern.compile("(?i)\\b(?:HWPX?|PDF|DOCX?|XLSX?|ZIP|PPTX?|JPEG|JPG|PNG)\\b");
    private static final Pattern CONDITION = Pattern.compile("해당|경우|한함|한하여|한해|택\\h*1|택일|중\\h*하나|제출\\h*(?:면제|생략|대상\\h*아님)|미제출|제출하지");
    private SubmissionDocumentDisplay() {}

    static List<String> names(String title) {
        String value = title.replaceFirst("^(?:[①-⑳□○◦●ㅇ•▸-]|\\d+[.)])\\h*", "")
                .replaceAll("[“”\\\"]", "").strip();
        String[] parts = value.split("\\h+(?:및|또는)\\h+|[,·ㆍ]\\h*|\\R");
        List<String> names = Arrays.stream(parts).map(String::strip).toList();
        if (names.size() < 2 || names.stream().anyMatch(part -> !SubmissionDocumentResolver.isDocument(part))) names = List.of(value);
        return names.stream().map(name -> name.replaceAll("\\h*[（(]해당\\h*시[)）]", "")
                .replaceAll("\\h*(?:각\\h*)?\\d+\\h*(?:부|매)$", "").strip()).distinct().toList();
    }

    static List<String> formats(String text) {
        if (text == null) return List.of();
        var result = new LinkedHashSet<String>();
        var matcher = FORMAT.matcher(text);
        while (matcher.find()) result.add(matcher.group().toUpperCase(java.util.Locale.ROOT));
        return List.copyOf(result);
    }

    static String readable(String text) {
        String value = SubmissionNoteFormatter.clean(text).replace('‧', '·');
        if (value.equals("기관 공문")) return "기관 명의의 공문을 준비해 주세요.";
        if (value.equals("기관장 직인본")) return "기관장 직인이 날인된 문서여야 합니다.";
        if (value.equals("운영·지원 사업계획서")) return "사업 운영·지원 계획을 작성해 주세요.";
        if (value.equals("사업계획서 기반 발표자료")) return "사업계획서를 바탕으로 발표자료를 준비해 주세요.";
        if (value.equals("(HWP 또는 PDF)")) return "HWP 또는 PDF 형식으로 제출합니다.";
        if (value.equals("사업계획 내 기재사항 증빙 등")) return "사업계획에 기재한 내용을 증빙하는 자료를 준비해 주세요.";
        if (value.matches("신청[·\\h]*접수기간 내 평가된 확인서")) return "신청·접수 기간 안에 평가받은 확인서를 준비해 주세요.";
        value = value.replaceFirst("^(.+?) 미제출 기관은 (.+?)로 발표 진행$", "$1를 제출하지 않은 기관은 $2로 발표합니다.")
                .replace("자율 제작(별도 양식 없음)", "자유롭게 작성합니다(별도 양식 없음)");
        return SubmissionNoteFormatter.format(value);
    }

    static SubmissionDocument prepare(SubmissionDocument item) {
        if (!item.documentNames().isEmpty()) return item;
        var names = names(item.title());
        var conditions = new LinkedHashSet<String>();
        var instructions = new ArrayList<String>();
        var formats = formats(item.detail());
        var quantity = Pattern.compile("(?:각\\h*)?\\d+\\h*(?:부|매)(?=$|\\h|[（(])").matcher(item.title());
        var counts = quantity.results().map(match -> match.group()).toList();
        if (!counts.isEmpty()) instructions.add("제출 수량: " + (counts.size() == 1 ? counts.getFirst() : item.title()));
        if (item.appliesTo() != null && !item.appliesTo().isBlank()) {
            String target = item.appliesTo().strip();
            // Older rows used the entire raw row as appliesTo; extract clauses rather than echoing it.
            if (target.equals(item.detail()) || target.contains("\n")) {
                for (String line : target.split("\\R")) if (CONDITION.matcher(line).find()) conditions.add(readable(line));
            } else if (!target.equals("원문에 명시된 제출 대상") && !target.equals("신청기관")) {
                conditions.add(CONDITION.matcher(target).find() ? readable(target) : "제출 대상: " + target);
            }
        }
        if (item.detail() != null) for (String raw : item.detail().split("\\R")) {
            String line = raw.replaceFirst("^[①-⑳□○◦●ㅇ•▸-]\\h*", "").strip();
            if (line.isBlank() || line.equals(item.title()) || names.contains(line)
                    || line.matches("[0-9○◯O●◎✓√X×✕✗\\h-]+") || line.matches("필수|해당\\h*시|선택")
                    || FORMAT.matcher(line).replaceAll("").matches("[()\\h/또는및·,]*")) continue;
            if (CONDITION.matcher(line).find()) conditions.add(readable(line));
            else instructions.add(readable(line));
        }
        if ("CONDITIONAL".equals(item.requirementLevel()) && conditions.isEmpty()) {
            conditions.add("원문에 ‘해당 시 제출’로 명시되어 있습니다. 구체적인 적용 대상은 원문을 확인해 주세요.");
        }
        if (item.title().contains("또는") || item.title().matches(".*(?:택\\h*1|중\\h*하나).*")) {
            conditions.add("목록의 서류 중 원문에 명시된 대안과 선택 조건에 맞춰 제출해 주세요.");
        }
        return new SubmissionDocument(item.title(), instructions.isEmpty() ? null : String.join(" ", instructions),
                item.requirementLevel(), item.stage(), item.appliesTo(), item.source(), names, List.copyOf(conditions), formats);
    }
}
