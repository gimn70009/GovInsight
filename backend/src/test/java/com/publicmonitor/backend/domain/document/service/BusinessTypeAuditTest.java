package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Offline source/format replay; this does not measure live model accuracy. */
class BusinessTypeAuditTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void 분류_평가_사례의_결과와_근거를_기록한다() throws Exception {
        var root = Path.of("src/test/resources");
        var cases = mapper.readTree(Files.readString(root.resolve("business-type/cases.json")));
        var rows = new ArrayList<Map<String, Object>>();
        var failures = new ArrayList<String>();
        for (var item : cases) {
            String body = item.path("body").asString("");
            var attachments = new ArrayList<DocumentAttachment>();
            if (item.has("sourceFile")) {
                String text = item.path("prefix").asString("") + Files.readString(root.resolve(item.path("sourceFile").asString()));
                attachments.add(file(text));
            }
            if (item.has("attachmentText")) attachments.add(file(item.path("attachmentText").asString()));
            if (item.path("duplicate").asBoolean(false)) attachments.add(attachments.getFirst());
            String saved = null;
            if (item.has("savedType")) {
                var evidence = new LinkedHashMap<String, Object>();
                evidence.put("origin", attachments.isEmpty() ? "NOTICE_BODY" : "ATTACHMENT");
                evidence.put("attachmentName", attachments.isEmpty() ? null : "공고문.pdf");
                evidence.put("excerpt", item.path("quote").asString());
                saved = mapper.writeValueAsString(Map.of("businessType", Map.of(
                        "primaryType", item.path("savedType").asString(), "explanation", "현재 지원 활동을 확인합니다.",
                        "details", List.of(), "sources", List.of(evidence))));
            }
            var resolution = BusinessTypeResolver.diagnose(item.path("title").asString(), body, attachments,
                    saved, item.path("documentType").asString(null), mapper);
            var result = resolution.summary();
            String expectedCode = item.path("expectedCode").asString(null);
            boolean passed = result.status().equals(item.path("expectedStatus").asString())
                    && java.util.Objects.equals(result.code(), expectedCode);
            if (!passed) failures.add(item.path("id").asString() + ": " + result.status() + "/" + result.code());
            var row = new LinkedHashMap<String, Object>();
            row.put("id", item.path("id").asString());
            row.put("kind", item.path("kind").asString());
            row.put("sourceGroup", item.path("sourceGroup").asString());
            row.put("expectedStatus", item.path("expectedStatus").asString());
            row.put("expectedCode", expectedCode);
            row.put("actualStatus", result.status());
            row.put("actualCode", result.code());
            row.put("passed", passed);
            row.put("evidence", result.sources());
            row.put("decisionReason", resolution.decisionReason());
            row.put("storedReason", resolution.storedReason());
            row.put("rejectedSourceIndex", resolution.rejectedSourceIndex());
            rows.add(row);
        }
        var output = Path.of("build/business-type-audit");
        Files.createDirectories(output);
        Files.writeString(output.resolve("current.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("kind", "offline_source_and_format_replay_not_live_model_accuracy", "cases", rows,
                        "total", rows.size(), "passed", rows.size() - failures.size())));
        assertThat(failures).as("분류 기대 결과 불일치").isEmpty();
    }

    private DocumentAttachment file(String text) {
        return DocumentAttachment.create(null, "공고문.pdf", "https://example.com/file", "pdf", "application/pdf",
                100L, "a".repeat(64), text, AttachmentParseStatus.COMPLETED, null);
    }
}
