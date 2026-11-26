package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SubmissionMatrixTest {

    @Test void emptyFirstMarkAndEntireRoleColumnStayUnresolved() throws Exception {
        var data = JsonMapper.builder().build().readTree(Files.readString(
                Path.of("../test-fixtures/document-tables/disaster-notice.json")));
        String text = data.get("submission").asString();
        for (int role = 2; role < 5; role++) {
            String[] lines = text.split("\\R");
            for (int i = 0; i < lines.length; i++) if (lines[i].startsWith("별첨1\t")) {
                var cells = lines[i].split("\\t", -1);
                cells[role] = ""; lines[i] = String.join("\t", cells);
            }
            var guide = StructuredSubmissionTable.read(String.join("\n", lines), null);
            assertThat(guide.documents()).hasSize(12);
            assertThat(guide.notes()).anyMatch(n -> n.text().contains("1개 행"));
        }
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) if (lines[i].startsWith("별첨")) {
            var cells = lines[i].split("\\t", -1);
            cells[4] = ""; lines[i] = String.join("\t", cells);
        }
        var guide = StructuredSubmissionTable.read(String.join("\n", lines), null);
        assertThat(guide.documents()).isEmpty();
        assertThat(guide.notes()).anyMatch(n -> n.text().contains("13개 행"));
    }

    @Test void realPublicMatrixKeepsAllRowsRequirementsAndRoleMarks() throws Exception {
        var data = JsonMapper.builder().build().readTree(Files.readString(
                Path.of("../test-fixtures/document-tables/disaster-notice.json")));
        for (String header : List.of("목록", "내역", "자료 이름")) {
            String text = data.get("submission").asString().replace("목록", header);
            var docs = SubmissionDocumentResolver.resolve(text, List.of(), null).documents();
            // The plain section also has the research plan.
            var matrix = docs.stream().filter(d -> d.source().sectionTitle().equals("제출서류 표 내 목록")).toList();
            assertThat(matrix).hasSize(13);
            for (int i = 0; i < matrix.size(); i++) {
                var row = matrix.get(i);
                assertThat(row.title()).isEqualTo(data.get("expected_titles").get(i).asString());
                assertThat(row.requirementLevel()).isEqualTo(data.get("expected_levels").get(i).asString());
                assertThat(row.stage()).isEqualTo("APPLICATION");
                for (var role : data.get("expected_roles").get(i))
                    assertThat(row.appliesTo()).contains(role.asString());
                assertThat(text).contains(row.source().excerpt());
            }
            assertThat(matrix.get(4).appliesTo()).isEqualTo("주관기관");
            assertThat(matrix.get(4).conditions()).contains("제출 대상 아님: 공동기관 · 위탁기관");
        }
    }

    @Test void brokenRoleAndUnparsedExceptionCannotBecomeMandatory() throws Exception {
        var data = JsonMapper.builder().build().readTree(Files.readString(
                Path.of("../test-fixtures/document-tables/disaster-notice.json")));
        String text = data.get("submission").asString();
        var docs = SubmissionDocumentResolver.resolve(text.replaceFirst("○\\tX\\tX", "○\t\tX"), List.of(), null).documents();
        assertThat(docs).noneMatch(d -> d.title().equals(data.get("expected_titles").get(4).asString()));
        assertThat(StructuredSubmissionTable.read(text + "\n※ 일부 기관은 제출 면제", null).documents()).isEmpty();
    }
}
