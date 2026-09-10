package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProposalDraftScopeTest {
    @Test
    void 실제_네_첨부와_공유_경계사례를_같은_기준으로_판정한다() throws Exception {
        var folder = Path.of("../test-fixtures/proposal-scope");
        var cases = new ObjectMapper().readTree(Files.readString(folder.resolve("cases.json")));
        for (var item : cases) {
            String text = item.has("file") ? Files.readString(folder.resolve(item.path("file").asText()))
                    : item.path("text").asText();
            assertThat(!ProposalDraftScope.exclusion(text).isEmpty()).as(item.path("name").asText())
                    .isEqualTo(item.path("excluded").asBoolean());
        }
    }
}
