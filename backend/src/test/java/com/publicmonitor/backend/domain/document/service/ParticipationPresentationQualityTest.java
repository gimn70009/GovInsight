package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ParticipationPresentationQualityTest {
    private final ParticipationFocusProjectionTest helper = new ParticipationFocusProjectionTest();
    private ParticipationGuide project(ParticipationGuide raw, Response response) {
        var evidence = new java.util.LinkedHashMap<String, List<RequirementSource>>();
        var sources = new java.util.ArrayList<Source>();
        for (var rule : raw.rules()) {
            String id = "s" + sources.size();
            evidence.put(id, rule.sources());
            sources.add(new Source(id, rule.sources().getFirst().excerpt(), "신청자격", List.of("BUSINESS_FORM")));
        }
        var input = new ParticipationRewriteProjection.Input(new Request(sources, List.of(), List.of()), evidence, false, null, sources);
        return ParticipationRewriteProjection.guide(raw, input, response, "READY");
    }
    @Test void emptyReadyCacheWithEvidenceIsIncompleteAndDiagnosticsSurviveSerialization() {
        var response = new Response(List.of(), List.of("s0"), "READY", List.of(),
                java.util.Map.of("candidates", 3, "accepted", 0, "QUOTE_NOT_IN_SOURCE", 3));
        var mapper = JsonMapper.builder().build();
        var restored = mapper.readValue(mapper.writeValueAsString(response), Response.class);
        assertThat(restored.diagnostics()).isEqualTo(response.diagnostics());
        var guide = project(helper.raw("대학과 연구기관은 신청할 수 있습니다."), restored);
        assertThat(guide.rewriteStatus()).isEqualTo("UNAVAILABLE");
        assertThat(guide.categories()).isNotEmpty();
        assertThat(helper.entries(guide)).isNotEmpty();
    }

    private static final String MAIN = "비영리법인인 기관이 신청할 수 있습니다.";

    @Test void badCachedDetailIsFlaggedWithoutLosingDefinitionsOrEvidence() {
        String definition = "법 제2조에 따라 설립된 법인이 포함됩니다.";
        String raw = "다만 대학 제외, 증빙 제출 필요";
        var response = new Response(List.of(new Statement(List.of("s0"), "BUSINESS_FORM", MAIN, "", List.of(MAIN),
                "ELIGIBILITY", null, null, List.of(
                    new Detail(List.of("s1"), definition, List.of(definition)),
                    new Detail(List.of("s2"), raw, List.of(raw))))), List.of(), "READY");
        var mapper = JsonMapper.builder().build();
        var guide = project(helper.raw(MAIN, definition, raw), mapper.readValue(mapper.writeValueAsString(response), Response.class));
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.text()).isEqualTo(MAIN);
            assertThat(rule.details()).containsExactly(definition);
            assertThat(rule.guidance()).containsExactly(ParticipationPresentationQuality.DETAIL_REVIEW);
            assertThat(rule.sources()).extracting(RequirementSource::excerpt).containsExactly(MAIN, definition, raw);
        });
        assertThat(helper.entries(guide).getFirst().supportingQuotes()).containsExactly(MAIN, definition, raw);
    }

    @Test void exactDuplicateCanBeHiddenButDifferentNumbersOrExceptionsCannot() {
        String main = "지분은 50% 이상이어야 합니다.";
        String extra = "공동소유는 지분이 30% 이상이면 인정합니다.";
        var guide = project(helper.raw(main, extra), new Response(List.of(new Statement(List.of("s0"), "IP", main, "",
                List.of(main), "ELIGIBILITY", null, null, List.of(
                    new Detail(List.of("s0"), main, List.of(main)), new Detail(List.of("s1"), extra, List.of(extra))))), List.of(), "READY"));
        assertThat(guide.rules().getFirst().details()).containsExactly(extra);
        assertThat(guide.rules().getFirst().guidance()).isEmpty();
    }

    @Test void repeatedCompleteSentenceIsHiddenWithoutDroppingItsEvidenceOrCache() {
        String repeated = "지분은 50% 이상이어야 합니다.";
        String main = "기업이 신청할 수 있습니다. " + repeated;
        String extra = "다만 대학은 신청 대상에서 제외됩니다.";
        var response = new Response(List.of(new Statement(List.of("s0"), "IP", main, "", List.of(main),
                "ELIGIBILITY", null, null, List.of(
                    new Detail(List.of("s1"), repeated, List.of(repeated)),
                    new Detail(List.of("s2"), extra, List.of(extra))))), List.of(), "READY");
        var mapper = JsonMapper.builder().build();
        String saved = mapper.writeValueAsString(response);
        var guide = project(helper.raw(main, repeated, extra), response);
        assertThat(guide.rules().getFirst().details()).containsExactly(extra);
        assertThat(helper.entries(guide).getFirst().supportingQuotes()).containsExactly(main, repeated, extra);
        assertThat(mapper.writeValueAsString(response)).isEqualTo(saved);
    }

    @Test void sharedWordsAndQuotesCannotHideNewLimitsNegationOrRoles() {
        String main = "기업은 지분이 50.5% 이상이어야 합니다. 대학은 신청할 수 없습니다.";
        var item = new Statement(List.of("s0"), "IP", main, "", List.of(main), "ELIGIBILITY");
        for (String extra : List.of("기업은 지분이 50.5% 미만이어야 합니다.",
                "기업은 지분이 50% 이상이어야 합니다.", "대학은 신청할 수 있습니다.",
                "연구기관은 신청할 수 없습니다.", "지분은 50.5% 이상이어야 합니다.",
                "기업은 지분이 50.5% 이상이어야 합니다. 증빙은 확인서로 제출합니다."))
            assertThat(ParticipationPresentationQuality.redundantDetail(item,
                    new Detail(List.of("s0"), extra, List.of(main)))).as(extra).isFalse();
        var hidden = new Statement(item.sourceIds(), item.criterion(), main, "", item.supportingQuotes(),
                item.kind(), null, null, List.of(), true);
        assertThat(ParticipationPresentationQuality.redundantDetail(hidden,
                new Detail(List.of("s0"), main, List.of(main)))).isFalse();
    }

    @Test void invalidMainHasReviewStatusWithItsOriginalEvidence() {
        String raw = "비영리법인인 기관";
        var guide = project(helper.raw(raw), new Response(List.of(new Statement(List.of("s0"), "BUSINESS_FORM", raw,
                "", List.of(raw), "ELIGIBILITY")), List.of(), "READY"));
        assertThat(helper.entries(guide).getFirst().displayStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(helper.entries(guide).getFirst().supportingQuotes()).containsExactly(raw);
    }

    @Test void adminSetupDependsOnTheConditionRatherThanTheSectionHeading() {
        String text = "접수 시스템의 기관등록과 담당자 권한부여가 필요합니다.";
        var item = new Statement(List.of("s0"), "BUSINESS_FORM", text, "", List.of(text), "ELIGIBILITY");
        var source = new RequirementSource("NOTICE_BODY", null, "가. 신청방법", null, text);
        assertThat(ParticipationPresentationQuality.procedureOnly(item, List.of(source))).isTrue();
        var qualifiedDetail = new Detail(List.of("s0"), "법에 따라 등록한 기관만 신청할 수 있습니다.",
                List.of("법에 따라 등록한 기관만 신청 가능"));
        var mixedDetails = new Statement(item.sourceIds(), item.criterion(), item.text(), item.groupLabel(),
                item.supportingQuotes(), item.kind(), null, null, List.of(qualifiedDetail));
        assertThat(ParticipationPresentationQuality.procedureOnly(mixedDetails, List.of(source))).isFalse();
        assertThat(ParticipationPresentationQuality.procedureOnly(item, List.of(
                new RequirementSource("NOTICE_BODY", null, "신청자격", null, text)))).isTrue();
        for (String qualification : List.of("사업자등록이 된 기업은 전산등록이 필요합니다.",
                "법 제12조에 따라 등록한 기업은 회원가입이 필요합니다.",
                "업력 7년 이내 기업은 계정 설정이 필요합니다.",
                "산업진흥법에 따라 등록한 사업자는 전산등록이 필요합니다.",
                "영업 허가를 받은 사업자는 계정 설정이 필요합니다.",
                "인증을 보유한 기업은 계정 설정이 필요합니다.",
                "체납기업은 참여가 제한되며 회원가입이 필요합니다.")) {
            var mixed = new Statement(List.of("s0"), "BUSINESS_FORM", qualification, "", List.of(qualification), "ELIGIBILITY");
            assertThat(ParticipationPresentationQuality.procedureOnly(mixed, List.of(source))).as(qualification).isFalse();
        }
    }

    @Test void readableSentencesPreserveDecimalsArticlesAndCompleteClauses() {
        for (String text : List.of("법 제2조 요건을 따릅니다.", "지분은 50.5% 이상이어야 합니다. 다만 대학은 제외됩니다."))
            assertThat(ParticipationPresentationQuality.completeSentence(text)).isTrue();
        for (String text : List.of("기관 등 단독 또는 컨소시엄", "증빙 제출 필요. 다만 대학은 제외됩니다.", "기관등록\n※ 사전 조치"))
            assertThat(ParticipationPresentationQuality.completeSentence(text)).isFalse();
    }
}
