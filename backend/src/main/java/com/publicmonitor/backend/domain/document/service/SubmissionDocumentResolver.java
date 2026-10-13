package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Preparation;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.PreparationItem;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionDocument;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionGuide;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionNote;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Supplements a proposal checklist with explicit submission sections from the same notice version. */
final class SubmissionDocumentResolver {
    private static final int MAX_TEXT = 200_000;
    private static final int MAX_BLOCK = 8_000;
    private static final String DOCUMENT = "체크리스트|계획서|신청서|공적조서|동의서|확인서|증명서|증명원|확약서|서약서|추천서|제안서|보고서|재무제표|등록증|등기부등본|등본|초본|증빙양식|증빙서류|증빙자료|이력서|사본";
    private static final Pattern TITLE = Pattern.compile("^.{0,150}?(?:" + DOCUMENT + ")[\\\"”’']?(?:\\h*(?:각\\h*)?\\d+\\h*(?:부|매|개))?(?:\\h*[（(][^\\n]{0,100}[)）])?\\h*$");
    private static final Pattern HEADING = Pattern.compile("^(?:(?:[□■○◦●ㅇ•*-]|\\d+[.)])\\h*)?[<\\[(（]?\\h*((?:(?:협약|선정|평가)[가-힣\\h]{0,12})?(?:제\\h*출|신\\h*청|접\\h*수|구\\h*비)\\h*(?:서\\h*류|자\\h*료)(?:\\h*(?:목록|안내))?)\\h*[>\\])）]?\\h*[:：]?\\h*(.*)$");
    private static final Pattern PREFIX = Pattern.compile("^(?:[①-⑳□○◦●ㅇ•-]|\\d+[.)])\\h*");
    private static final Pattern REFERENCE = Pattern.compile("^[\\[(（](?:붙임|별첨|첨부|서식|별지|양식)\\h*(?:제\\h*)?[\\d, .-]+(?:호)?[\\])）]\\h*");
    private static final Pattern COLUMN = Pattern.compile("(?:유형\\h*\\d+|주관(?:연구개발)?기관|공동(?:연구개발)?기관|영리기관|비영리기관|개인기업|법인기업)");
    private static final Pattern MARK = Pattern.compile("[○◯O●◎✓√X×✕✗-]", Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTLINE = Pattern.compile("^(?:[□■]|\\d+[.)]\\h*|[가-하][.)]\\h*)");
    private static final Pattern BOUNDARY = Pattern.compile("^(?:[<\\[(（]?\\h*)?(?:문의처|유의사항|서류\\h*작성|신청\\h*방법|제출\\h*방법|평가\\h*방법|선정\\h*절차|지원\\h*내용|첨부\\h*파일|붙임\\h*\\d|서식\\h*\\d|별지\\h*\\d)");
    private static final Pattern BLOCKED = Pattern.compile("^(?:[□■\\[<(]|\\d+[.)])?\\h*(?:참고\\h*자료|작성\\h*예시|작성\\h*견본|양식\\h*목록|첨부\\h*목록)");
    private static final Pattern EXCLUDED_FILE = Pattern.compile("(?i)예시|견본|FAQ|참고자료|신청서|계획서|동의서|서식|양식|운영지침");
    private static final Pattern CONDITIONAL = Pattern.compile("해당\\h*(?:시|자|기업|기관)|경우|한하여|한해|한함|대상만|기관만|기업만|택\\h*1|택일|중\\h*하나");
    private static final Pattern EXEMPTION = Pattern.compile("제출\\h*(?:불필요|면제|생략)|제출하지|미제출|해당\\h*없음");

    private SubmissionDocumentResolver() {}

    static SubmissionGuide resolve(String body, List<DocumentAttachment> attachments, Preparation preparation) {
        var documents = new LinkedHashMap<String, SubmissionDocument>();
        var notes = new LinkedHashMap<String, SubmissionNote>();
        collect(body, null, documents, notes);
        for (var attachment : attachments) {
            String name = attachment.getFileName();
            if (attachment.getParseStatus() != AttachmentParseStatus.COMPLETED
                    || (EXCLUDED_FILE.matcher(name).find() && !name.matches(".*(?:공고|모집|안내).*"))) continue;
            collect(attachment.getExtractedText(), name, documents, notes);
        }
        var result = new ArrayList<>(documents.values());
        if (preparation != null && preparation.submissionDocuments() != null) {
            for (var item : preparation.submissionDocuments()) {
                if (!grounded(item)) continue;
                // Explicit current-source rows keep their type/period/recipient conditions over a generic saved title.
                if (result.stream().anyMatch(raw -> sameDocument(raw, item))) continue;
                result.add(new SubmissionDocument(item.title(), item.detail(), item.requirementLevel(),
                        item.stage(), item.appliesTo(), item.source()));
            }
        }
        return new SubmissionGuide(List.copyOf(result), List.copyOf(notes.values()));
    }

    private static boolean grounded(PreparationItem item) {
        return item != null && item.title() != null && !item.title().isBlank()
                && !"RECOMMENDED".equals(item.requirementLevel()) && item.source() != null
                && ("NOTICE_BODY".equals(item.source().origin()) || "ATTACHMENT".equals(item.source().origin()))
                && item.source().excerpt() != null && !item.source().excerpt().isBlank();
    }

    private static boolean sameDocument(SubmissionDocument raw, PreparationItem saved) {
        boolean stage = raw.stage() == null || saved.stage() == null || raw.stage().equals(saved.stage());
        String a = titleKey(raw.title()), b = titleKey(saved.title());
        return stage && (a.equals(b) || (b.length() >= 4 && a.contains(b)));
    }

    private static void collect(String text, String fileName, LinkedHashMap<String, SubmissionDocument> documents,
                                LinkedHashMap<String, SubmissionNote> notes) {
        if (text == null || text.isBlank()) return;
        boolean truncated = text.length() > MAX_TEXT;
        String bounded = text.substring(0, Math.min(text.length(), MAX_TEXT));
        String[] lines = bounded.replace('\u00a0', ' ').replaceAll("([①-⑳])", "\n$1").split("\\R");
        Block block = null;
        String context = "";
        var precedingNotes = new ArrayList<String>();
        boolean blocked = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isBlank()) continue;
            if (line.startsWith("[파일:") || line.startsWith("[일부 원문 생략")) {
                // The final row may have lost an exception after a truncation/file boundary.
                block = null;
                blocked = true;
                continue;
            }
            if (BLOCKED.matcher(line).find()) {
                if (block != null) emit(block, fileName, documents, notes);
                block = null;
                blocked = true;
                continue;
            }
            var heading = HEADING.matcher(line);
            boolean submissionHeading = heading.matches() && (heading.group(2).isBlank() || isDocument(heading.group(2)));
            if (submissionHeading && !blocked) {
                if (block == null || block.hasRows()) {
                    if (block != null) emit(block, fileName, documents, notes);
                    block = new Block(line, stage(heading.group(1), context));
                    block.lines.addAll(precedingNotes);
                }
                if (!heading.group(2).isBlank()) block.lines.add(heading.group(2));
                continue;
            }
            if (!isDocument(line) && (OUTLINE.matcher(line).find() || BOUNDARY.matcher(line).find())) {
                if (block != null) emit(block, fileName, documents, notes);
                block = null;
                context = line;
                precedingNotes.clear();
                continue;
            }
            if (block == null && line.length() <= 500 && (line.startsWith("*") || line.startsWith("◦"))
                    && line.contains("서류") && line.contains("제출")) {
                if (precedingNotes.size() == 3) precedingNotes.removeFirst();
                precedingNotes.add(line);
            }
            if (block != null) {
                block.lines.add(line);
                block.size += line.length();
                if (block.size > MAX_BLOCK) block.invalid = true;
            }
        }
        if (block != null && !truncated) emit(block, fileName, documents, notes);
    }

    private static final class Block {
        final String heading;
        final String stage;
        final List<String> lines = new ArrayList<>();
        int size;
        boolean invalid;
        Block(String heading, String stage) { this.heading = heading; this.stage = stage; }
        boolean hasRows() { return lines.stream().anyMatch(SubmissionDocumentResolver::isDocument); }
    }

    private static final class Row {
        final String original;
        final String title;
        final List<String> cells = new ArrayList<>();
        Row(String original) { this.original = original; this.title = cleanTitle(original); }
    }

    private static void emit(Block block, String fileName, LinkedHashMap<String, SubmissionDocument> documents,
                             LinkedHashMap<String, SubmissionNote> notes) {
        if (block.invalid || block.lines.isEmpty()) return;
        var columns = new ArrayList<String>();
        var rows = new ArrayList<Row>();
        var shared = new ArrayList<String>();
        Row row = null;
        for (String line : block.lines) {
            if (line.matches("^[*※].*") || line.matches("^(?:모든|공통)\\h*서류.*") || (rows.isEmpty() && line.contains("서류") && line.contains("제출") && !isDocument(line))) {
                shared.add(line);
            } else if (isDocument(line)) {
                row = new Row(line);
                rows.add(row);
            } else if (row == null) {
                var names = COLUMN.matcher(line);
                if (names.replaceAll("").replaceAll("[\\h|]", "").isEmpty()) {
                    names.reset();
                    while (names.find()) columns.add(names.group());
                }
            } else {
                row.cells.add(line);
            }
        }
        if (rows.isEmpty()) return;
        boolean accepted = false;
        for (var entry : rows) {
            String value = String.join("\n", entry.cells);
            List<String> markers = entry.cells.stream().flatMap(line -> List.of(line.strip().split("\\h+")).stream())
                    .filter(cell -> MARK.matcher(cell).matches()).toList();
            boolean matrix = !columns.isEmpty() || !markers.isEmpty();
            if (matrix && (columns.isEmpty() || markers.size() != columns.size()
                    || entry.cells.stream().anyMatch(line -> !line.matches("[○◯O●◎✓√X×✕✗\\h-]+")))) continue;
            var included = new ArrayList<String>();
            var excluded = new ArrayList<String>();
            if (matrix) {
                for (int i = 0; i < columns.size(); i++) {
                    (markers.get(i).matches("[○◯O●◎✓√o]") ? included : excluded).add(columns.get(i));
                }
                if (included.isEmpty()) continue;
            }
            String instruction = entry.original + (matrix || value.isBlank() ? "" : "\n" + value);
            if (instruction.length() > 800 || instruction.matches(".*(?:예시|견본|참고용).*")) continue;
            boolean conditional = CONDITIONAL.matcher(instruction).find() || !excluded.isEmpty();
            if (EXEMPTION.matcher(instruction).find() && !conditional) continue;
            String level = instruction.contains("선택") ? "OPTIONAL" : conditional ? "CONDITIONAL" : "MANDATORY";
            if (instruction.contains("권장")) continue;
            for (String note : shared) {
                boolean applies = titleKey(note).contains(titleKey(entry.title))
                        || note.matches(".*(?:모든|전체)\\h*서류.*");
                if (!applies) continue;
                if (note.contains("선택")) level = "OPTIONAL";
                else if (EXEMPTION.matcher(note).find() || CONDITIONAL.matcher(note).find()) level = "CONDITIONAL";
            }
            String appliesTo = matrix ? String.join(" · ", included) : conditional ? instruction : null;
            String detail = instruction;
            if (!excluded.isEmpty()) detail += "\n" + String.join(" · ", excluded) + ": 제출 대상 아님";
            String quote = block.heading + "\n" + (columns.isEmpty() ? "" : String.join(" | ", columns) + "\n")
                    + entry.original + (value.isBlank() ? "" : "\n" + value);
            var document = new SubmissionDocument(entry.title, detail, level, block.stage, appliesTo,
                    source(fileName, block.heading, quote));
            String key = titleKey(entry.title) + ":" + level + ":" + key(appliesTo);
            // Repeated generic lists do not erase a known stage; distinct known stages remain separate.
            boolean knownStage = documents.entrySet().stream().anyMatch(item -> item.getKey().startsWith(key + ":") && item.getValue().stage() != null);
            if (document.stage() != null) documents.remove(key + ":");
            if (document.stage() != null || !knownStage) documents.putIfAbsent(key + ":" + key(document.stage()), document);
            accepted = true;
        }
        if (accepted) {
            for (String note : shared) {
                if (note.length() <= 1000) notes.putIfAbsent(key(note), new SubmissionNote(note, source(fileName, block.heading, block.heading + "\n" + note)));
            }
        }
    }

    private static RequirementSource source(String fileName, String heading, String quote) {
        return new RequirementSource(fileName == null ? "NOTICE_BODY" : "ATTACHMENT", fileName, heading, null, quote);
    }

    private static boolean isDocument(String line) {
        if (line.matches("^[*※].*")) return false;
        return TITLE.matcher(cleanTitle(line)).matches();
    }

    private static String cleanTitle(String text) {
        String title = PREFIX.matcher(text.strip()).replaceFirst("");
        title = REFERENCE.matcher(title).replaceFirst("");
        return title.replace("“", "").replace("”", "").replace("\"", "").strip();
    }

    private static String stage(String heading, String context) {
        String value = heading.matches(".*(?:신청|접수|협약|선정|평가).*") ? heading : context;
        if (value.matches(".*(?:협약|계약).*(?:시|체결|단계|서류).*")) return "AGREEMENT";
        if (value.matches(".*선정\\h*(?:후|이후).*")) return "POST_SELECTION";
        if (value.matches(".*평가\\h*(?:시|단계).*")) return "EVALUATION";
        if (value.matches(".*(?:신청|접수).*")) return "APPLICATION";
        return null;
    }

    private static String titleKey(String text) {
        return key(cleanTitle(text).replaceAll("\\h*(?:각\\h*)?\\d+\\h*(?:부|매)(?=$|\\h|[（(])", ""));
    }

    private static String key(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("[\\s\\p{Punct}“”‘’·ㆍ]", "").toLowerCase(java.util.Locale.ROOT);
    }
}
