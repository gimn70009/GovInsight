package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import java.util.regex.Pattern;

/** Includes explicit performance requirements that used to be hidden in the eligibility-only view. */
final class ParticipationCategorySources {
    private static final Pattern FORM = Pattern.compile("예시|견본|FAQ|참고자료|참고\\s*\\d+|신청서|계획서|동의서|서식|양식|운영지침", Pattern.CASE_INSENSITIVE);
    private ParticipationCategorySources() {}

    static boolean allowed(String name) {
        return name != null && (!FORM.matcher(name).find() || name.matches(".*(?:공고|모집|안내).*"));
    }

    static List<String> documents(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("(?m)(?=^" + Pattern.quote("[파일:") + ")")).filter(part -> {
            if (!part.startsWith("[파일:")) return true;
            int end = part.indexOf(']');
            return end > 0 && allowed(part.substring(4, end).strip());
        }).toList();
    }

    static boolean incomplete(String body, List<DocumentAttachment> files) {
        var documents = new ArrayList<>(documents(body));
        for (var file : files) {
            if (!allowed(file.getFileName())) continue;
            String text = file.getExtractedText();
            if (file.getParseStatus() != AttachmentParseStatus.COMPLETED || text == null || text.isBlank()) return true;
            documents.addAll(documents(text));
        }
        return documents.isEmpty() || documents.stream().anyMatch(text -> text.contains("[일부 원문 생략")
                || text.indexOf(0xFFFD) >= 0 || text.length() > 200_000);
    }

    static List<ParticipationRule> supplement(String body, List<DocumentAttachment> files, List<ParticipationRule> existing) {
        var result = new ArrayList<ParticipationRule>();
        for (String document : documents(body)) collect(document, null, result, existing);
        for (var file : files) if (allowed(file.getFileName()) && file.getParseStatus() == AttachmentParseStatus.COMPLETED)
            for (String document : documents(file.getExtractedText())) collect(document, file.getFileName(), result, existing);
        return result;
    }

    private static void collect(String text, String file, List<ParticipationRule> result, List<ParticipationRule> existing) {
        if (text == null || text.isBlank()) return;
        var source = new ParticipationSourceLines(text.substring(0, Math.min(200_000, text.length())));
        String[] lines = source.lines();
        boolean blocked = false;
        var context = new ParticipationSectionContext(true);
        String heading = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.startsWith("[파일:")) {
                int end = line.indexOf(']');
                blocked = end < 0 || !allowed(line.substring(4, end).strip());
                heading = null;
                context.excludes(line);
                continue;
            }
            if (line.matches("^(?:붙임|별첨|별지|서식|양식|작성예시|작성견본|참고자료)\\s*\\d*.*")
                    || line.startsWith("[일부 원문 생략")) blocked = true;
            if (blocked || context.excludes(line) || line.isBlank()) continue;
            if (line.matches("^(?:[□■]|\\d+[.)]\\s).*") && line.length() < 80) heading = line;
            boolean table = line.equals("[표]");
            int end = i + 1;
            if (table) {
                while (end < lines.length && !lines[end].strip().equals("[/표]")
                        && !lines[end].startsWith("[파일:")) end++;
                if (end >= lines.length || !lines[end].strip().equals("[/표]")) continue;
                end++;
            } else {
                while (end < lines.length && !lines[end].isBlank()
                        && !lines[end].strip().matches("^(?:[□■○◦●ㅇo•*※\\-]|\\d+[.)]\\s|[①-⑳]|\\[).*")) end++;
            }
            String quote = source.excerpt(i, end);
            i = end - 1; // A table is consumed once; its header and rows must not become prose on the next iteration.
            if (table && quote.length() > 12_000) continue;
            var evidence = new RequirementSource(file == null ? "NOTICE_BODY" : "ATTACHMENT", file, heading, null, quote);
            String compact = quote.replaceAll("\\s+", " ");
            if (table) {
                if (compact.matches(".*지원\\s*자격.*")) {
                    for (String row : quote.split("\\R"))
                        for (String cell : row.split("\\t"))
                            if (cell.matches(".*(?:구성|컨소시[엄움]).*(?:필수|이상).*")
                                    || cell.matches(".*이상.*기관.*구성.*"))
                                add("BASIC", "협력 구조", ParticipationExecutionClauses.display(cell.strip()), List.of(), evidence, result, existing);
                } else if (ParticipationExecutionClauses.funding(compact.replace("[표]", "").replace("[/표]", ""))) {
                    var sentences = ParticipationFundingTable.sentences(quote);
                    if (sentences.isEmpty())
                        add("FUNDING", "자부담 능력", "유형별 자부담 기준", List.of(quote), evidence, result, existing);
                    else for (String sentence : sentences)
                        add("FUNDING", "자부담 능력", sentence, List.of(), evidence, result, existing);
                } else if (ParticipationCriterion.PLAN.topic.matcher(compact).find()
                        && compact.matches(".*(?:평가\\s*항목|배점|평가\\s*기준).*")) {
                    add("BASIC", "사업 수행 의지·계획", "사업계획서·발표평가 기준", List.of(quote), evidence, result, existing);
                } else if (compact.matches(".*(?:제출\\s*서류명|서류\\s*유형).*")) {
                    for (String row : quote.split("\\R")) {
                        String[] cells = row.split("\\t");
                        if (cells.length >= 3 && cells[1].contains("필수")
                                && ParticipationCriterion.PLAN.topic.matcher(cells[1]).find())
                            add("BASIC", "사업 수행 의지·계획",
                                    cells[1].replace("[필수]", "").strip() + "를 필수 제출해야 합니다.",
                                    List.of("제출 방법: " + cells[2].strip()), evidence, result, existing);
                    }
                } else {
                    // Some HWP tables contain paragraphs repeated in merged cells, rather than a criterion matrix.
                    for (String cell : new LinkedHashSet<>(Arrays.asList(quote.split("[\\t\\r\\n]+"))))
                        collectProse(cell, evidence, result, existing);
                }
            } else collectProse(quote, evidence, result, existing);
        }
    }

    private static void collectProse(String quote, RequirementSource evidence,
            List<ParticipationRule> result, List<ParticipationRule> existing) {
        for (String part : ParticipationExecutionClauses.split(quote)) {
            String text = part.replaceAll("\\s+", " ").strip().replaceFirst("^[○◦●ㅇo•\\-]\\s*", "");
            if (text.isBlank() || text.startsWith("[표]") || ParticipationClauseText.caption(text)) continue;
            boolean funding = ParticipationExecutionClauses.funding(text);
            boolean composition = text.matches(".*(?:컨소시[엄움]|독립된 기관|공동연구개발기관).*(?:필수|이상.{0,35}구성|구성.{0,35}필수).*")
                    && !text.matches(".*(?:예시|예를|참고|서명|서류|확인서).*");
            if (!funding && !ParticipationExecutionClauses.plan(text) && !composition) continue;
            add(funding ? "FUNDING" : "BASIC", funding ? "자부담 능력" : composition ? "협력 구조" : "사업 수행 의지·계획",
                    ParticipationExecutionClauses.display(text), List.of(), evidence, result, existing);
        }
    }

    private static void add(String section, String label, String text, List<String> details, RequirementSource evidence,
            List<ParticipationRule> result, List<ParticipationRule> existing) {
        if (label.equals("협력 구조") && existing.stream().filter(rule -> !rule.section().equals("REVIEW"))
                .flatMap(rule -> rule.sources().stream()).anyMatch(source -> source.excerpt() != null
                        && source.excerpt().contains(evidence.excerpt()))) return;
        if (result.stream().anyMatch(rule -> rule.text().equals(text) && rule.sources().contains(evidence))
                || existing.stream().anyMatch(rule -> rule.section().equals(section) && rule.text().equals(text)
                    && rule.sources().contains(evidence))) return;
        result.add(new ParticipationRule(section, label, text, null, null, details, List.of(), List.of(),
                List.of(), List.of(evidence), null));
    }
}
