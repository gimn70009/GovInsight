package com.publicmonitor.backend.domain.document.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import static com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;

class ParticipationDisplayQualityTest {
    private String fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/participation-conditions/display-source-quality.json")) {
            return new ObjectMapper().readTree(stream).get(name).asText();
        }
    }
    private ParticipationGuide project(String body) {
        var c = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(c.guide(), false, c.scopes());
        return ParticipationRewriteProjection.guide(c.guide(), input, Response.unavailable(input.request()), "UNAVAILABLE");
    }
    @Test void modelBackendAndFrontendShareTheSameDisplayDamageContract() throws Exception {
        try (var stream = getClass().getResourceAsStream("/participation-conditions/display-quality.json")) {
            for (var row : new ObjectMapper().readTree(stream)) {
                assertThat(ParticipationDisplayQuality.readable(row.get("text").asText()))
                        .as(row.get("name").asText()).isEqualTo(row.get("readable").asBoolean());
            }
        }
    }
    @Test void damagedOcrIsEvidenceOnlyAndNeverRepairedByDeletingRepeatedSyllables() throws Exception {
        String damaged = fixture("damaged");
        var guide = project("2. 공통사항\n" + damaged);
        assertThat(guide.rules()).isEmpty();
        assertThat(guide.categories().stream().flatMap(c -> c.rows().stream()).flatMap(r -> r.entries().stream()))
                .anyMatch(e -> "EVIDENCE_ONLY".equals(e.displayStatus())
                        && e.rule().sources().stream().anyMatch(s -> s.excerpt().contains(damaged)));
    }
    @Test void intactOriginalWinsOverDamagedOcrAndCachedSummaryKeepsTheActualException() throws Exception {
        String damaged = fixture("damaged"), intact = fixture("intact");
        String body = "2. 공통사항\n" + damaged + "\n[문서 전체 텍스트 추출]\n2. 공통사항\n" + intact;
        var c = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(c.guide(), false, c.scopes());
        String quote = intact.lines().findFirst().orElseThrow();
        String id = input.request().sources().stream().filter(s -> s.excerpt().contains(quote)).findFirst().orElseThrow().id();
        String incomplete = quote.replaceFirst("^□ \\(신청자격\\) ", "") + "이어야 합니다.";
        var response = new Response(List.of(new Statement(List.of(id), "BUSINESS_AGE", incomplete,
                "", List.of(quote), "ELIGIBILITY")), List.of(), "READY");
        var guide = ParticipationRewriteProjection.guide(c.guide(), input, response, "READY");
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.scope()).isEqualTo("공통");
            assertThat(rule.text()).contains("업력 7년 이상의 중소기업", "세부 프로그램별 신청요건", "업력 7년 미만",
                    "창업자에 해당하지 않는", "마일스톤트랙 추천기업은 신청대상에 포함됩니다.")
                    .doesNotContain("년 년", "소기기업");
        });
        assertThat(guide.categories().stream().flatMap(g -> g.rows().stream()).flatMap(r -> r.entries().stream())
                .filter(e -> "VERIFIED".equals(e.displayStatus())))
                .singleElement().satisfies(e -> assertThat(e.supportingQuotes().getFirst()).contains("※", "업력 7년 미만"));
    }
    @Test void aWrappedRestrictionAfterTheFirstEntityIsNotCutOff() {
        var guide = project("□ 신청자격\n업력 7년 이상의 중소기업\n중 세부 프로그램별 신청요건을 충족하는 기업");
        assertThat(guide.rules()).singleElement().satisfies(r ->
                assertThat(r.text()).isEqualTo("업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업이 지원 대상입니다."));
    }
    @Test void compoundSectionHeadingIsNeverGluedToTheFollowingCondition() {
        var guide = project("□ 신청자격 및 방법\n◦ 신청자격 :「판로지원법」제2조제2호에 따른 공공기관\n◦ 신청방법 : 온라인 신청");
        assertThat(guide.rules()).singleElement().satisfies(r ->
                assertThat(r.text()).isEqualTo("판로지원법 제2조제2호에 따른 공공기관이 지원 대상입니다."));
    }
    @Test void prependedVisualPagesDoNotTurnASecondaryBuyerIntoTheApplicant() {
        String intro = "[PDF 6페이지 시각 판독]\n" + "참고 설명 ".repeat(150)
                + "\n[문서 전체 텍스트 추출]\n중소기업 제품의 공공판로 개척을 지원하는 공고\n4\n구매기관 현황 및 신청\n";
        String clause = "□ 신청자격 및 방법\n◦ 신청자격 :「판로지원법」제2조제2호에 따른 공공기관\n◦ 신청방법 : 온라인 신청";
        var c = ParticipationConditionResolver.candidatesWithScopes(intro + clause, List.of(), null);
        var input = ParticipationRewriteProjection.input(c.guide(), false, c.scopes());
        var source = input.evidence().values().stream().flatMap(List::stream).filter(s -> s.excerpt().contains(clause)).findFirst().orElseThrow();
        // Previously paid identities stay unchanged; only the display context uses the original introduction.
        assertThat(c.scopes().context(List.of(source))).doesNotContain("별도 참여 역할");
        assertThat(c.scopes().displayContext(List.of(source))).contains("별도 참여 역할");
        assertThat(ParticipationRewriteProjection.guide(c.guide(), input, Response.unavailable(input.request()), "UNAVAILABLE").rules()).isEmpty();
        assertThat(project(intro.replace("중소기업 제품의 공공판로 개척을 지원하는 공고", "구매기관 모집 공고. 중소기업 제품의 판로 지원") + clause).rules())
                .singleElement().satisfies(r -> assertThat(r.text()).contains("공공기관"));
    }
    @Test void rawQuoteAndMalformedCachedModelTextCannotSkipTheSameQualityChecks() {
        String fragment = "업력 7년 이상인 기업 중 다음 요건에 해당하는";
        assertThat(ParticipationFocusedText.prepare("BUSINESS_AGE", fragment, fragment, "신청자격", "ELIGIBILITY")).isNull();
        String damaged = "업력 7년 년 이상인 중소기업이 지원 대상입니다.";
        assertThat(ParticipationFocusedText.prepare("BUSINESS_AGE", damaged, damaged, "신청자격", "ELIGIBILITY")).isNull();
        String complete = "업력 7년 이상인 기업만 신청할 수 있습니다.";
        assertThat(ParticipationFocusedText.prepare("BUSINESS_AGE", complete, complete, "신청자격", "ELIGIBILITY")).isNotNull();
    }
    @Test void unsupportedAdjacentExceptionCannotBeOmittedFromALocalOrCachedSentence() {
        String quote = "업력 7년 이상의 중소기업";
        String body = "□ 신청자격\n" + quote + "\n※ 단, 별표에 따른 예외를 반드시 확인";
        assertThat(project(body).rules()).isEmpty();
        assertThat(ParticipationDisplayQuality.completeQuote(body, quote)).isFalse();
        assertThat(ParticipationDisplayQuality.completeQuote("□ 신청자격\n" + quote + "\n※ 세부 안내는 홈페이지 참조", quote)).isTrue();
    }
    @Test void aCompleteModelRewriteUsesTheSameFieldAsItsIdenticalLocalCondition() {
        String quote = "업력 7년 이상의 매출액 500억원 달성 중소기업";
        var c = ParticipationConditionResolver.candidatesWithScopes("1. 성장형\n□ 지원대상\n" + quote, List.of(), null);
        var input = ParticipationRewriteProjection.input(c.guide(), false, c.scopes());
        String id = input.request().sources().stream().filter(s -> s.excerpt().contains(quote)).findFirst().orElseThrow().id();
        var response = new Response(List.of(new Statement(List.of(id), "BUSINESS_FORM", quote + "이어야 합니다.",
                "", List.of(quote), "ELIGIBILITY")), List.of(), "READY");
        var guide = ParticipationRewriteProjection.guide(c.guide(), input, response, "READY");
        assertThat(guide.rules()).singleElement().satisfies(r -> {
            assertThat(r.label()).isEqualTo("매출·수출");
            assertThat(r.text()).isEqualTo(quote + "이어야 합니다.");
        });
    }
    @Test void blankLinesAndAnEarlierHarmlessFootnoteCannotHideALaterException() {
        for (String gap : List.of("\n", "\n\n", "\n※ 안내 문구\n\n")) {
            String body = "□ 지원대상\n중소기업" + gap + "※ 단, 개별 기업이 아닌 컨소시엄 단위로 신청";
            assertThat(project(body).rules()).isEmpty();
            assertThat(ParticipationDisplayQuality.completeQuote(body, "중소기업")).isFalse();
        }
    }
    @Test void revenueMeasurementPeriodAndAdditionalTrackNotesAreShownWithoutChangingTheirValues() {
        String main = "업력 7년 이상의 매출액 100억원 달성* 중소기업";
        String period = "최근 3개년(‘23~’25년) 표준재무제표증명원상 매출액 100억원 1회 이상 달성 기업";
        String track = "마일스톤트랙 : 매출 100억원을 달성한 창업 3년 미만의 창업지원 사업 참여기업을 대상으로, 운영기관의 추천을 통해 별도 선발(10개사 내)";
        String body = "1. 성장형\n□ 지원대상\n" + main + "\n\n* " + period + "\n** " + track;
        var guide = project(body);
        assertThat(guide.rules()).singleElement().satisfies(r -> {
            assertThat(r.label()).isEqualTo("매출·수출");
            assertThat(r.details()).containsExactly(period, track);
        });
        assertThat(guide.categories().stream().flatMap(c -> c.rows().stream()).flatMap(r -> r.entries().stream())
                .filter(e -> "VERIFIED".equals(e.displayStatus())))
                .singleElement().satisfies(e -> assertThat(e.supportingQuotes().getFirst()).contains(period, track));
        assertThat(ParticipationDisplayQuality.completeQuote(body, main)).isFalse();
    }
    @Test void wrappedHeadcountAndTheSeparateNumberedRequirementsRemainVisible() {
        String body = "가. 신청대상\n\n◦ 소공인\n (상시근로자수 10인 미만의 제조업을 영위하는 소상공인)\n\n"
                + "- 신청조건 ①, ②를 모두 충족하여야 함\n① 한국표준산업분류 제조업(C10~C34)을 주된 사업으로\n 신청 영위중이어야 함\n 조건\n② 상시근로자 수 10인 미만의 소상공인\n";
        var guide = project(body);
        assertThat(guide.rules()).singleElement().satisfies(r -> {
            assertThat(r.text()).contains("상시근로자수 10인 미만", "제조업");
            assertThat(r.label()).isEqualTo("신청 주체");
            assertThat(String.join(" ", r.details())).contains("①, ②를 모두", "C10~C34", "영위중이어야 함", "10인 미만")
                    .doesNotContain("신청 영위");
        });
    }

    @Test void theSameAdditionalRequirementsFromPdfAndStructuredTableHaveTheSameDisplayNotes() {
        String header = "가. 신청대상\n◦ 소공인\n(상시근로자수 10인 미만의 제조업을 영위하는 소상공인)\n";
        String first = "① 한국표준산업분류 제조업(C10~C34)을 주된 사업으로 영위중이어야 함";
        String second = "② 상시근로자 수 10인 미만의 소상공인";
        String list = "- 신청조건 ①, ②를 모두 충족하여야 함\n";
        var plain = project(header + list + first + "\n" + second).rules().getFirst();
        var table = project(header + list + "[표]\n신청 ⏎ 조건\t" + first + "\n신청 ⏎ 조건\t" + second + "\n[/표]").rules().getFirst();
        assertThat(table.details()).isEqualTo(plain.details());
        assertThat(table.details().getFirst()).doesNotContain("[표]", "[/표]", "⏎", "\t");
        assertThat(table.sources().getFirst().excerpt()).contains("[표]", "신청 ⏎ 조건\t");
    }

}
