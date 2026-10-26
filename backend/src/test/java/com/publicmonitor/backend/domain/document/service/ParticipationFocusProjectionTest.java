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

    @Test void exactSourceFragmentsNeedNoSentenceRewritingAndPreferenceStaysInItsOwnField() {
        String age = "업력 7년 이내";
        String patent = "등록 특허 보유 시 2점 가점";
        var raw = raw(age, patent);
        var response = new Response(List.of(
                new Statement(List.of("s0"), "BUSINESS_AGE", age, "", List.of(age), "ELIGIBILITY"),
                new Statement(List.of("s1"), "IP", patent, "", List.of(patent), "PREFERENCE")), List.of(), "READY");
        var guide = project(raw, response);
        assertThat(guide.categories()).singleElement().satisfies(category -> {
            assertThat(category.code()).isEqualTo("COMPANY_REQUIREMENTS");
            assertThat(category.rows()).extracting(ParticipationCriterionRow::code)
                    .containsExactly("BUSINESS_AGE", "REVENUE", "IP", "SECTOR", "BUSINESS_FORM", "EXPERIENCE", "CREDIT");
        });
        assertThat(entries(guide)).extracting(e -> e.rule().section()).containsExactly("BASIC", "PREFERENCE");
        assertThat(entries(guide)).extracting(e -> e.rule().text()).containsExactly(age, patent);
        assertThat(guide.conditions()).isEmpty();
        assertThat(guide.rules()).hasSize(2);
    }

    @Test void identicalQuotationCanBelongToBothFieldsAndRequirementKinds() {
        String quote = "업력 7년 이내이며 매출액 10억원 이상인 기업은 신청 가능하며 평가 시 우대";
        var raw = raw(quote);
        var response = new Response(List.of(
                new Statement(List.of("s0"), "BUSINESS_AGE", quote, "", List.of(quote), "ELIGIBILITY"),
                new Statement(List.of("s0"), "BUSINESS_AGE", quote, "", List.of(quote), "PREFERENCE"),
                new Statement(List.of("s0"), "REVENUE", quote, "", List.of(quote), "ELIGIBILITY")), List.of(), "READY");
        assertThat(entries(project(raw, response))).hasSize(3).allMatch(e -> "VERIFIED".equals(e.displayStatus()));
    }

    @Test void unrelatedKindsAndRewrittenOrInventedTextCannotReachVerifiedCells() {
        String quote = "특허를 보유한 기업 우대";
        var raw = raw(quote);
        for (Statement statement : List.of(
                new Statement(List.of("s0"), "IP", quote, "", List.of(quote), "EXCLUSION"),
                new Statement(List.of("s0"), "IP", "특허 3건 이상이 필수입니다.", "", List.of(quote), "ELIGIBILITY"),
                new Statement(List.of("s0"), "IP", "특허 3건 이상", "", List.of("특허 3건 이상"), "ELIGIBILITY"),
                new Statement(List.of("s0"), "TAX", quote, "", List.of(quote), "PREFERENCE"))) {
            var guide = project(raw, new Response(List.of(statement), List.of(), "READY"));
            assertThat(entries(guide)).singleElement().satisfies(e -> {
                assertThat(e.displayStatus()).isEqualTo("EVIDENCE_ONLY");
                assertThat(e.rule().text()).isEmpty();
                assertThat(e.rule().sources().getFirst().excerpt()).isEqualTo(quote);
            });
        }
    }

    @Test void emptyOrUnprocessedExtractionDoesNotClaimConditionsAreAbsent() {
        for (String status : List.of("READY", "UNAVAILABLE")) {
            var guide = project(raw("매출액 10억원 이상"), new Response(List.of(), List.of("s0"), status));
            assertThat(guide.categories().getFirst().rows()).hasSize(7)
                    .allMatch(row -> row.status().equals("REVIEW_REQUIRED"));
            assertThat(entries(guide)).singleElement().satisfies(e -> assertThat(e.displayStatus()).isEqualTo("EVIDENCE_ONLY"));
        }
    }

    @Test void unrelatedNoticesDoNotStartAnAiRequest() {
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, new ObjectMapper());
        try {
            var version = DocumentVersion.create(null, 1, "공고", "대표자 국세 체납 시 제외", "a".repeat(64), null, 0, null);
            var guide = service.resolve(version, raw("대표자 국세 체납 시 제외"), false);
            assertThat(guide.rewriteStatus()).isEqualTo("READY");
            assertThat(guide.categories().getFirst().rows()).hasSize(7);
            assertThat(entries(guide)).isEmpty();
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void wholeTablesAndScopeHeadingsArePreservedForClassification() {
        String table = "[표]\n유형\t업력\t매출액\n일반형\t7년 이내\t10억원 이상\n특화형\t10년 이내\t20억원 이상\n[/표]";
        var input = ParticipationRewriteProjection.input(raw(table), false);
        assertThat(input.request().sources()).singleElement().satisfies(source -> {
            assertThat(source.excerpt()).isEqualTo(table);
            assertThat(source.groupLabel()).isEqualTo("신청 자격과 우대 사항");
            assertThat(source.criteria()).containsExactly("BUSINESS_AGE", "REVENUE");
        });
    }
    @Test void operatorRecruitmentContextSurvivesTheSelectedBeneficiaryAgeBlock() throws Exception {
        String body = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/participation-conditions/startup-structured.txt"));
        var raw = ParticipationConditionResolver.candidates(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(raw, false);
        assertThat(input.request().sources()).anySatisfy(source -> {
            assertThat(source.excerpt()).contains("업력 10년 이내 기업");
            assertThat(source.groupLabel()).contains("주관기관 모집 공고", "창업기업 모집은 2027년 별도 공고 예정", "[원문 구역]", "지원대상");
        });
        assertThat(ParticipationRewriteProjection.legacyInput(raw, false, null).request().sources()).noneSatisfy(source ->
                assertThat(source.excerpt()).startsWith("□ 신청 제외대상"));
    }

    @Test void trackHeadingRemainsSeparateFromTheExactQuotation() {
        String quote = "매출액 100억원 이상";
        var source = new RequirementSource("NOTICE_BODY", null,
                "[공고 대상] 주관기관 모집 공고\n[원문 구역] □ 스케일업 100억 트랙", null, quote);
        var rule = new ParticipationRule("EVIDENCE", "원문", "", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(source));
        var raw = new ParticipationGuide(List.of(), List.of(rule), List.of(), List.of());
        var response = new Response(List.of(new Statement(List.of("s0"), "REVENUE", quote,
                source.sectionTitle(), List.of(quote), "ELIGIBILITY")), List.of(), "READY");
        assertThat(entries(project(raw, response))).singleElement().satisfies(entry -> {
            assertThat(entry.rule().text()).isEqualTo(quote);
            assertThat(entry.rule().scope()).isEqualTo("스케일업 100억 트랙");
        });
    }


    @Test void readableSentenceUsesOriginalQuoteForGroundingAndScope() {
        String quote = "제조업(C10~C34)을 주된 사업으로 영위중이어야 함";
        String text = "제조업(C10~C34)을 주된 사업으로 영위해야 합니다.";
        var guide = project(raw(quote), new Response(List.of(
                new Statement(List.of("s0"), "SECTOR", text, "", List.of(quote), "ELIGIBILITY")), List.of(), "READY"));
        assertThat(entries(guide)).singleElement().satisfies(e -> {
            assertThat(e.rule().text()).isEqualTo(text);
            assertThat(e.supportingQuotes()).containsExactly(quote);
            assertThat(e.rule().sources().getFirst().excerpt()).isEqualTo(quote);
            assertThat(e.displayStatus()).isEqualTo("VERIFIED");
        });
    }

    @Test void cachedCleanTableKeepsOnlyTheActualIndustryCell() throws Exception {
        String body = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/test/resources/participation-conditions/clean-structured.txt"));
        String quote = body.substring(body.indexOf("가. 신청대상"), body.indexOf("1. “도시형소공인”")).strip();
        var guide = project(raw(quote), new Response(List.of(
                new Statement(List.of("s0"), "SECTOR", quote, "", List.of(quote), "ELIGIBILITY")), List.of(), "READY"));
        assertThat(entries(guide).stream().filter(e -> "VERIFIED".equals(e.displayStatus())).toList()).singleElement().satisfies(e -> {
            assertThat(e.rule().text()).contains("C10~C34", "주된 사업").doesNotContain("[표]", "상시근로자", "제2조");
            assertThat(e.supportingQuotes()).singleElement().satisfies(q -> assertThat(quote).contains(q));
            assertThat(e.rule().sources().getFirst().excerpt()).isEqualTo(quote);
        });
    }

    @Test void cachedDocumentListCannotBecomeAnIndustryCondition() throws Exception {
        String quote = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/submission-documents/clean.txt"));
        quote = quote.replace("\r\n", "\n");
        quote = quote.substring(0, quote.indexOf("\n3\n택1")).strip();
        var guide = project(raw(quote), new Response(List.of(
                new Statement(List.of("s0"), "SECTOR", quote, "", List.of(quote), "ELIGIBILITY")), List.of(), "READY"));
        assertThat(entries(guide)).allMatch(e -> !"VERIFIED".equals(e.displayStatus()));
    }

    @Test void sentenceCannotAlterNumericUnitsThresholdOrderOrPreference() {
        for (String text : List.of("매출액은 100만원 이상이어야 합니다.", "매출액은 100억원 미만이어야 합니다.",
                "매출액 100억원 이상이면 우대합니다.")) {
            String quote = "매출액 100억원 이상";
            var guide = project(raw(quote), new Response(List.of(
                    new Statement(List.of("s0"), "REVENUE", text, "", List.of(quote), "ELIGIBILITY")), List.of(), "READY"));
            assertThat(entries(guide)).allMatch(e -> !"VERIFIED".equals(e.displayStatus()));
        }
    }
    @Test void preferenceAndNegationSurviveReadableSentences() {
        var display = ParticipationFocusedText.prepare("IP", "등록 특허 보유 기업을 우대합니다.",
                "등록 특허 보유 기업", "우대사항", "PREFERENCE");
        assertThat(display).isNotNull();
        assertThat(ParticipationFocusedText.prepare("IP", "등록 특허가 있어야 합니다.",
                "등록 특허가 없어야 함", "신청자격", "ELIGIBILITY")).isNull();
    }

    @Test void extendedEligibilityCandidatesAndVerifiedSentencesReachTheMatchingRows() {
        String[][] cases = {
            {"BUSINESS_FORM", "공공기관에 해당하는 법인 또는 단체", "공공기관에 해당하는 법인 또는 단체여야 합니다."},
            {"CREDIT", "일반기업의 경우 기업신용평가등급 B 이상", "일반기업의 경우 기업신용평가등급 B 이상이어야 합니다."},
            {"EXPERIENCE", "최근 1년간 공공기관 계약실적 10억원 이하", "최근 1년간 공공기관 계약실적은 10억원 이하여야 합니다."},
            {"IP", "우수연구개발 기술개발제품 12종", "우수연구개발 기술개발제품 12종에 해당해야 합니다."},
            {"SECTOR", "경제안보품목 및 경제안보서비스의 도입 생산 및 제공에 기여하고자 하는 사업자 또는 사업자단체", "경제안보품목 및 경제안보서비스의 도입 생산 및 제공에 기여하고자 하는 사업자 또는 사업자단체여야 합니다."},
            {"REVENUE", "전년도 직접수출액 5,000만불 미만의 중소기업", "전년도 직접수출액이 5,000만불 미만인 중소기업이어야 합니다."}
        };
        for (var example : cases) {
            var input = ParticipationRewriteProjection.input(raw(example[1]), false);
            assertThat(input.request().sources()).singleElement().satisfies(source -> assertThat(source.criteria()).contains(example[0]));
            var guide = project(raw(example[1]), new Response(List.of(new Statement(List.of("s0"), example[0], example[2], "", List.of(example[1]), "ELIGIBILITY")), List.of(), "READY"));
            assertThat(guide.rules()).singleElement().satisfies(rule -> assertThat(rule.text()).isEqualTo(example[2]));
        }
    }
    @Test void compoundAgeAndRevenueCanSplitWithoutChangingOrConditionsOrGrades() {
        String quote = "업력 7년 이상의 매출액 500억원 달성 중소기업";
        assertThat(ParticipationFocusedText.prepare("BUSINESS_AGE", "업력 7년 이상이어야 합니다.", quote, "지원대상", "ELIGIBILITY")).isNotNull();
        assertThat(ParticipationFocusedText.prepare("REVENUE", "매출액 500억원을 달성해야 합니다.", quote, "지원대상", "ELIGIBILITY")).isNotNull();
        assertThat(ParticipationFocusedText.prepare("BUSINESS_AGE", "업력 7년 이상이어야 합니다.", quote + " 또는 특허 보유", "지원대상", "ELIGIBILITY")).isNull();
        assertThat(ParticipationFocusedText.prepare("CREDIT", "신용등급 A 이상이어야 합니다.", "신용등급 B 이상", "지원대상", "ELIGIBILITY")).isNull();
    }
}
