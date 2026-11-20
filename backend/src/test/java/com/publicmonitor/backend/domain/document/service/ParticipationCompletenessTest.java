package com.publicmonitor.backend.domain.document.service;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

class ParticipationCompletenessTest {
    @Test void sharedCompletenessCases() throws Exception {
        var mapper = JsonMapper.builder().build();
        for (var c : mapper.readTree(Files.readString(Path.of("../test-fixtures/participation-completeness-cases.json")))) {
            var details = new ArrayList<String>(); var quotes = new ArrayList<String>();
            c.path("details").forEach(v -> details.add(v.asString()));
            c.path("quotes").forEach(v -> quotes.add(v.asString()));
            assertThat(ParticipationCompleteness.reviewReason(c.path("text").asString(), details, quotes,
                    c.path("kind").asString())).as(c.path("name").asString()).isEqualTo(c.path("reason").asString(null));
        }
    }

    @Test void oldIncompleteCacheRequiresReviewWithoutRewritingItsFacts() {
        String quote = "연구기관\t「연구기관법」 제2조에 따른 기관 또는 「산업혁신법」 제42조에 따른 연구소";
        var data = ParticipationConditionResolver.candidatesWithScopes("□ 신청자격\n[표]\n구분\t자격\n" + quote + "\n[/표]", List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        var source = input.availableSources().stream().filter(s -> s.excerpt().contains(quote)).findFirst().orElseThrow();
        String text = "연구기관법에 따른 연구기관이어야 합니다.";
        var item = new Statement(List.of(source.id()), "BUSINESS_FORM", text, "", List.of(quote), "ELIGIBILITY");
        var guide = ParticipationRewriteProjection.guide(data.guide(), input, new Response(List.of(item), List.of(), "READY"), "READY");
        assertThat(guide.categories().getFirst().rows().stream().flatMap(r -> r.entries().stream())
                .filter(e -> !e.displayStatus().equals("EVIDENCE_ONLY")).toList()).singleElement().satisfies(e -> {
            assertThat(e.displayStatus()).isEqualTo("REVIEW_REQUIRED");
            assertThat(e.rule().text()).isEqualTo(text);
            assertThat(e.rule().guidance()).anyMatch(s -> s.contains("법령별 자격 범위"));
            assertThat(e.supportingQuotes()).contains(quote);
        });
    }
}
