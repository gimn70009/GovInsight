package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.entity.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GroupedSubmissionTableTest {
    @Test void sharedCasesKeepDocumentNamesScopesAlternativesAndStages() throws Exception {
        var cases = JsonMapper.builder().build().readTree(Files.readString(
                Path.of("../test-fixtures/document-tables/grouped-submission-cases.json")));
        for (var test : cases) {
            String text = test.get("text").asString();
            var guide = SubmissionDocumentResolver.resolve(text, List.of(), null);
            var titles = new ArrayList<String>();
            var levels = new ArrayList<String>();
            var stages = new ArrayList<String>();
            test.get("titles").forEach(n -> titles.add(n.asString()));
            test.get("levels").forEach(n -> levels.add(n.asString()));
            test.get("stages").forEach(n -> stages.add(n.asString()));
            assertThat(guide.documents()).as(test.get("id").asString()).extracting(d -> d.title()).containsExactlyElementsOf(titles);
            assertThat(guide.documents()).extracting(d -> d.requirementLevel()).containsExactlyElementsOf(levels);
            assertThat(guide.documents()).extracting(d -> d.stage()).containsExactlyElementsOf(stages);
            assertThat(guide.documents()).allSatisfy(d -> assertThat(text).contains(d.source().excerpt()));
            var file = DocumentAttachment.create(null, "모집 안내.hwpx", "https://example.test/file", "hwpx", null, null, null, text, AttachmentParseStatus.COMPLETED, null);
            assertThat(SubmissionDocumentResolver.resolve("", List.of(file), null).documents()).hasSameSizeAs(guide.documents());
        }
    }
}
