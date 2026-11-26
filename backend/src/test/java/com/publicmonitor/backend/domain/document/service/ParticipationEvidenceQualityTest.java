package com.publicmonitor.backend.domain.document.service;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

class ParticipationEvidenceQualityTest {
    @Test void sharedEvidenceCases() throws Exception {
        var cases = JsonMapper.builder().build().readTree(Files.readString(Path.of("../test-fixtures/participation-evidence-cases.json")));
        for (var c : cases) assertThat(ParticipationEvidenceQuality.rejection(c.path("text").asString(),
                c.path("quote").asString(), c.path("kind").asString(), c.path("heading").asString(),
                c.path("detail").asBoolean())).as(c.path("name").asString()).isEqualTo(c.path("reason").asString(null));
    }

    @Test void rejectsOldCacheWithWrongDocumentEvenThoughQuoteIsVerbatim() {
        var data = ParticipationConditionResolver.candidatesWithScopes("□ 신청서류\n사업자등록증 1부\n서약서 1부", List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        var source = input.availableSources().getFirst();
        var item = new Statement(List.of(source.id()), "BUSINESS_FORM", "사업자등록증을 제출할 수 있어야 합니다.", "",
                List.of("서약서 1부"), "ELIGIBILITY");
        var guide = ParticipationRewriteProjection.guide(data.guide(), input, new Response(List.of(item), List.of(), "READY"), "READY");
        assertThat(guide.rules()).isEmpty();
        assertThat(guide.rewriteStatus()).isEqualTo("UNAVAILABLE");
    }
}
