package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionDocument;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionGuide;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionNote;
import java.util.ArrayList;
import java.util.List;

/** Reads the declared name/content/remarks columns before the generic list reader can merge them. */
final class SubmissionContentTable {
    private record Row(String marker, String name, String content, List<String> remarks) {}
    private SubmissionContentTable() {}

    static SubmissionGuide read(List<String> lines, String heading, String stage, String fileName) {
        int header = -1;
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (lines.get(i).replace(" ", "").equals("주요내용") && lines.get(i + 1).equals("비고")) { header = i; break; }
        }
        if (header < 0) return null;
        var rows = new ArrayList<Row>();
        var footnotes = new ArrayList<String>();
        String marker = null;
        int cursor = header + 2;
        while (cursor < lines.size()) {
            String line = lines.get(cursor);
            if (line.matches("[*※].*")) { footnotes.add(line); cursor++; continue; }
            if (line.matches("필수|해당\\h*시|선택")) { marker = line; cursor++; continue; }
            // An incomplete table is not re-read as a generic list of document-shaped sentences.
            if (!SubmissionDocumentResolver.isDocument(line) || cursor + 1 >= lines.size()) break;
            String content = lines.get(cursor + 1);
            if (content.matches("필수|해당\\h*시|선택|[*※].*")) break;
            cursor += 2;
            var remarks = new ArrayList<String>();
            while (cursor < lines.size()) {
                String next = lines.get(cursor);
                if (next.matches("필수|해당\\h*시|선택|[*※].*|\\d+") || SubmissionDocumentResolver.isDocument(next)) break;
                remarks.add(next); cursor++;
            }
            rows.add(new Row(marker, line, content, remarks));
        }
        lines.subList(0, header).stream().filter(SubmissionNoteFormatter::common).forEach(footnotes::add);
        var documents = new ArrayList<SubmissionDocument>();
        var notes = new ArrayList<SubmissionNote>();
        for (var row : rows) {
            String level = row.marker == null ? null : row.marker.equals("필수") ? "MANDATORY" : row.marker.equals("선택") ? "OPTIONAL" : "CONDITIONAL";
            var conditions = new ArrayList<String>();
            var instructions = new ArrayList<String>();
            var formats = SubmissionDocumentDisplay.formats(String.join(" ", row.remarks));
            String target = null;
            if (!row.content.equals(row.name)) instructions.add(SubmissionDocumentDisplay.readable(row.content));
            for (String remark : row.remarks) {
                if (!SubmissionDocumentDisplay.formats(remark).isEmpty()) {
                    // Format alternatives and signature requirements belong to the same remark.
                    if (!remark.matches("[()A-Za-z\\h/또는및·,]+")) instructions.add(SubmissionDocumentDisplay.readable(remark));
                    else if (remark.contains("또는")) instructions.add(SubmissionDocumentDisplay.readable(remark));
                    continue;
                }
                if ("CONDITIONAL".equals(level)) {
                    if (remark.matches("[가-힣 ·]+(?:기관|기업|법인|개인|신청자|사업자|대상자)")) {
                        target = remark;
                        conditions.add("제출 대상: " + remark);
                    } else conditions.add(SubmissionDocumentDisplay.readable(remark));
                } else instructions.add(SubmissionDocumentDisplay.readable(remark));
            }
            var ownNotes = footnotes.stream().filter(note -> SubmissionNoteFormatter.clean(note).startsWith(row.name)
                    || SubmissionNoteFormatter.common(note)).toList();
            for (String note : ownNotes) {
                if (SubmissionNoteFormatter.common(note)) {
                    conditions.add(SubmissionDocumentDisplay.readable(note));
                    if (note.contains("선택")) level = "OPTIONAL";
                    else if (note.matches(".*(?:해당|경우|면제|생략|제출하지|미제출|불필요).*") ) level = "CONDITIONAL";
                } else if (note.matches(".*(?:미제출|경우|대신|대체|면제|해당).*") ) {
                    conditions.add(SubmissionDocumentDisplay.readable(note));
                } else instructions.add(SubmissionDocumentDisplay.readable(note));
            }
            if ("CONDITIONAL".equals(level) && conditions.isEmpty()) conditions.add("원문에 ‘해당 시 제출’로 명시되어 있습니다. 구체적인 적용 대상은 원문을 확인해 주세요.");
            String quote = heading + "\n제출 서류\n주요내용\n비고\n" + (row.marker == null ? "" : row.marker + "\n")
                    + row.name + "\n" + row.content + (row.remarks.isEmpty() ? "" : "\n" + String.join("\n", row.remarks))
                    + (ownNotes.isEmpty() ? "" : "\n" + String.join("\n", ownNotes));
            var source = new RequirementSource(fileName == null ? "NOTICE_BODY" : "ATTACHMENT", fileName, heading, null, quote);
            documents.add(new SubmissionDocument(row.name, instructions.isEmpty() ? null : String.join(" ", instructions), level, stage, target, source,
                    SubmissionDocumentDisplay.names(row.name), List.copyOf(conditions), formats));
        }
        for (String note : lines.subList(0, header)) {
            if (SubmissionNoteFormatter.actionable(note) && note.matches("[*※◦].*")) {
                notes.add(new SubmissionNote(SubmissionNoteFormatter.format(note), null,
                        new RequirementSource(fileName == null ? "NOTICE_BODY" : "ATTACHMENT", fileName, heading, null, heading + "\n" + note)));
            }
        }
        for (String note : footnotes) {
            if (!lines.subList(0, header).contains(note) && SubmissionNoteFormatter.actionable(note) && SubmissionNoteFormatter.selfContained(note)
                    && rows.stream().noneMatch(row -> SubmissionNoteFormatter.clean(note).startsWith(row.name))) {
                notes.add(new SubmissionNote(SubmissionDocumentDisplay.readable(note), null,
                        new RequirementSource(fileName == null ? "NOTICE_BODY" : "ATTACHMENT", fileName, heading, null, heading + "\n" + note)));
            }
        }
        return new SubmissionGuide(List.copyOf(documents), List.copyOf(notes));
    }
}
