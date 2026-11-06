package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ParticipationFocusProjectionTest {
    ParticipationGuide raw(String... excerpts) {
        var rules = java.util.Arrays.stream(excerpts).map(text -> new ParticipationRule("EVIDENCE", "원문", "", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(
                        new RequirementSource("NOTICE_BODY", null, "신청 자격과 우대 사항", null, text)))).toList();
        return new ParticipationGuide(List.of(), rules, List.of(), List.of());
    }
    List<ParticipationCriterionEntry> entries(ParticipationGuide guide) {
        return guide.categories().stream().flatMap(c -> c.rows().stream()).flatMap(r -> r.entries().stream()).toList();
    }
    ParticipationGuide project(ParticipationGuide raw, Response response) {
        return ParticipationRewriteProjection.guide(raw, ParticipationRewriteProjection.input(raw, false), response, response.status());
    }

    @Test void legacyVocabularyRemainsFrozenForCacheReuse() {
        var input = ParticipationRewriteProjection.legacyInput(raw(
                "업력 7년 이내 기업", "매출액 10억원 이상 기업", "등록 특허를 보유한 기업 우대", "관련 산업 납품 실적 보유 기업에 가점",
                "사업자등록 후 7년 이내 기업", "AI 관련 기업", "지원 대상은 전력 분야 기업",
                "대표자 국세 체납 시 제외", "중소기업만 신청 가능", "산업통상자원부 담당 부서와 문의 전화",
                "실용신안 보유 기업에 가점", "지식재산권 보유 기업 우대"), false, null);
        assertThat(input.request().sources()).hasSize(7);
        assertThat(input.request().sources()).extracting(Source::criteria)
                .containsExactly(List.of("BUSINESS_AGE"), List.of("REVENUE"), List.of("IP"), List.of("SECTOR"),
                        List.of("BUSINESS_AGE"), List.of("SECTOR"), List.of("SECTOR"));
        assertThat(input.request().criteria()).extracting(Criterion::code)
                .containsExactly("BUSINESS_AGE", "REVENUE", "IP", "SECTOR");
    }

    @Test void modelTextClassificationScopeAndAudiencePassThroughWithoutLocalRewriting() {
        String quote = "업력 7년 이상인 중소기업. 단, 추천받은 창업기업은 업력 제한 없음";
        String text = "중소기업은 업력이 7년 이상이어야 합니다. 다만 추천받은 창업기업에는 업력 제한이 없습니다.";
        var response = new Response(List.of(new Statement(List.of("s0"), "BUSINESS_FORM", text, "일반 절 제목",
                List.of(quote), "ELIGIBILITY", "성장형", "도입기업")), List.of(), "READY");
        var guide = project(raw(quote), response);
        assertThat(entries(guide)).singleElement().satisfies(entry -> {
            assertThat(entry.displayStatus()).isEqualTo("VERIFIED");
            assertThat(entry.rule().text()).isEqualTo(text);
            assertThat(entry.rule().label()).isEqualTo("신청 주체");
            assertThat(entry.rule().scope()).isEqualTo("성장형");
            assertThat(entry.rule().audience()).isEqualTo("도입기업");
            assertThat(entry.rule().details()).isEmpty();
            assertThat(entry.supportingQuotes()).containsExactly(quote);
        });
    }

    @Test void oldCachedSingleClausesGainOnlyAnEndingAndKeepTheirMissingScope() {
        String age = "업력 7년 이내";
        String patent = "등록 특허 보유 시 2점 가점";
        var response = new Response(List.of(
                new Statement(List.of("s0"), "BUSINESS_AGE", age, "신청자격 및 신청요건", List.of(age), "ELIGIBILITY"),
                new Statement(List.of("s1"), "IP", patent, "", List.of(patent), "PREFERENCE")), List.of(), "READY");
        var guide = project(raw(age, patent), response);
        assertThat(guide.rules()).extracting(ParticipationRule::text).containsExactly(age + "이어야 합니다.", patent + "을 부여합니다.");
        assertThat(guide.rules()).allSatisfy(rule -> {
            assertThat(rule.scope()).isNull();
            assertThat(rule.audience()).isNull();
        });
        assertThat(guide.rules()).extracting(ParticipationRule::section).containsExactly("BASIC", "PREFERENCE");
    }

    @Test void invalidSourceIdsQuotesEnumsAndEmptyTextStayEvidenceOnly() {
        String quote = "특허를 보유한 기업 우대";
        var data = raw(quote);
        for (Statement statement : List.of(
                new Statement(List.of("s0"), "IP", quote, "", List.of(quote), "EXCLUSION"),
                new Statement(List.of("s0"), "TAX", quote, "", List.of(quote), "PREFERENCE"),
                new Statement(List.of("missing"), "IP", quote, "", List.of(quote), "PREFERENCE"),
                new Statement(List.of("s0"), "IP", quote, "", List.of("없는 근거"), "PREFERENCE"),
                new Statement(List.of("s0"), "IP", " ", "", List.of(quote), "PREFERENCE"),
                new Statement(List.of(), "IP", quote, "", List.of(quote), "PREFERENCE"),
                new Statement(null, "IP", quote, "", List.of(quote), "PREFERENCE"),
                new Statement(List.of("s0"), "IP", quote, "", null, "PREFERENCE"))) {
            var guide = project(data, new Response(List.of(statement), List.of(), "READY"));
            assertThat(guide.rules()).isEmpty();
            assertThat(entries(guide)).singleElement().satisfies(entry -> {
                assertThat(entry.displayStatus()).isEqualTo("EVIDENCE_ONLY");
                assertThat(entry.rule().text()).isEmpty();
                assertThat(entry.rule().sources().getFirst().excerpt()).isEqualTo(quote);
            });
        }
    }

    @Test void everySourceIdMustHaveItsOwnMatchingQuotation() {
        String first = "특허 보유 기업", second = "매출액 10억원 이상";
        var guide = project(raw(first, second), new Response(List.of(
                new Statement(List.of("s0", "s1"), "IP", "특허 보유 기업을 우대합니다.", "", List.of(first), "PREFERENCE")), List.of(), "READY"));
        assertThat(guide.rules()).isEmpty();
        assertThat(entries(guide)).hasSize(2).allMatch(entry -> entry.displayStatus().equals("EVIDENCE_ONLY"));
    }

    @Test void allGroundedQuotesAreRetainedAcrossSeveralSourceIds() {
        String first = "업력 7년 이상", second = "다만 추천받은 창업기업은 업력 제한 없음";
        String text = "업력이 7년 이상이어야 합니다. 추천받은 창업기업에는 업력 제한이 없습니다.";
        var guide = project(raw(first, second), new Response(List.of(
                new Statement(List.of("s0", "s1"), "BUSINESS_AGE", text, "", List.of(first, second), "ELIGIBILITY")), List.of(), "READY"));
        assertThat(entries(guide)).singleElement().satisfies(entry -> {
            assertThat(entry.supportingQuotes()).containsExactly(first, second);
            assertThat(entry.rule().sources()).hasSize(2);
            assertThat(entry.rule().text()).isEqualTo(text);
        });
    }

    @Test void missingExtractionDoesNotGenerateConditionsOrClaimThatNoConditionsExist() {
        for (String status : List.of("READY", "UNAVAILABLE")) {
            var guide = project(raw("매출액 10억원 이상"), new Response(List.of(), List.of("s0"), status));
            assertThat(guide.rules()).isEmpty();
            assertThat(guide.categories().getFirst().rows()).hasSize(7)
                    .allMatch(row -> row.status().equals("REVIEW_REQUIRED"));
            assertThat(entries(guide)).singleElement().satisfies(entry -> assertThat(entry.displayStatus()).isEqualTo("EVIDENCE_ONLY"));
        }
    }

    @Test void partialExtractionRetainsUnresolvedRawSectionsBesideAcceptedStatements() {
        String quote = "업력 7년 이상";
        var guide = project(raw(quote, "매출액 10억원 이상"), new Response(List.of(
                new Statement(List.of("s0"), "BUSINESS_AGE", "업력이 7년 이상이어야 합니다.", "", List.of(quote), "ELIGIBILITY")),
                List.of("s1"), "READY"));
        assertThat(guide.rules()).hasSize(1);
        assertThat(entries(guide)).extracting(ParticipationCriterionEntry::displayStatus).containsExactly("VERIFIED", "EVIDENCE_ONLY");
    }

    @Test void unrelatedNoticesDoNotStartAnAiRequest() {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, new ObjectMapper());
        try {
            var version = DocumentVersion.create(null, 1, "공고", "대표자 국세 체납 시 제외", "a".repeat(64), null, 0, null);
            var guide = service.resolve(version, ParticipationConditionResolver.candidates("대표자 국세 체납 시 제외", List.of(), null), false);
            assertThat(guide.rewriteStatus()).isEqualTo("READY");
            assertThat(entries(guide)).isEmpty();
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void wholeTablesAndOriginalScopeHeadingsRemainUnchangedInFrozenRequest() {
        String table = "[표]\n유형\t업력\t매출액\n일반형\t7년 이내\t10억원 이상\n특화형\t10년 이내\t20억원 이상\n[/표]";
        var input = ParticipationRewriteProjection.input(raw(table), false);
        assertThat(input.request().sources()).singleElement().satisfies(source -> {
            assertThat(source.excerpt()).isEqualTo(table);
            assertThat(source.groupLabel()).isEqualTo("신청 자격과 우대 사항");
            assertThat(source.criteria()).containsExactly("BUSINESS_AGE", "REVENUE");
        });
    }

    @Test void oldStatementJsonWithoutScopeOrAudienceRemainsReadable() {
        String json = "{\"source_ids\":[\"s0\"],\"criterion\":\"IP\",\"text\":\"특허 기업 우대\",\"group_label\":\"우대\",\"supporting_quotes\":[\"특허 기업\"],\"kind\":\"PREFERENCE\"}";
        var item = new ObjectMapper().readValue(json, Statement.class);
        assertThat(item.scope()).isNull();
        assertThat(item.audience()).isNull();
    }
}
