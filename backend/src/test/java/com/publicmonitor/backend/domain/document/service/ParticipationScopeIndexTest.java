package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ParticipationScopeIndexTest {
    private final String quote = "업력 7년 이상인 기업만 신청할 수 있음";

    private String scope(String body, String text) {
        var sources = ParticipationRawSources.collectWithScopes(body, List.of());
        var evidence = sources.rules().stream().flatMap(rule -> rule.sources().stream())
                .filter(source -> source.excerpt().contains(text)).distinct().toList();
        return sources.scopes().scope(evidence, text);
    }

    @Test void parentTrackSurvivesGenericSubsectionsAndContainsNoSectionNumbers() {
        for (String prefix : List.of("3 점프업1000\n3-1. 사업개요", "3.점프업1000\n3-1.사업개요",
                "3\n점프업1000\n3-1. 사업개요", "점프업1000\n3-1. 사업개요")) {
            assertThat(scope(prefix + "\n□ 지원대상\n" + quote, quote)).isEqualTo("점프업1000");
        }
    }

    @Test void rootCommonAndTrackLocalCommonAreNotConfused() {
        assertThat(scope("1. 공통사항\n□ 신청자격\n" + quote, quote)).isEqualTo("공통");
        assertThat(scope("3. 점프업500\n3-1 사업개요\n□ 공통 신청자격\n" + quote, quote))
                .isEqualTo("점프업500 · 공통");
        assertThat(scope("3. 점프업500\n□ 안내\n다른 안내입니다.\n4. 공통사항\n□ 신청자격\n" + quote, quote))
                .isEqualTo("공통");
    }

    @Test void genericHeadingsAndConditionsCannotBecomeTrackNames() {
        assertThat(scope("1. 사업개요\n□ 지원대상\n" + quote, quote)).isNull();
        assertThat(scope("12\n임의의 문장입니다.\n□ 지원대상\n" + quote, quote)).isNull();
        assertThat(scope("- 3 -\n□ 지원대상\n" + quote, quote)).isNull();
        String body = "3. 점프업500\n□ 신청자격\n1. 등록 특허 보유 기업\n2. 바이오 분야 기업\n" + quote;
        assertThat(scope(body, quote)).isEqualTo("점프업500");
    }

    @Test void tableNumbersAndTableOfContentsDoNotLeakIntoConditions() {
        String body = "목차\n1. 점프업100 .... 3\n16. 문의처 28\n□ 근거법령\n관련 규정 안내\n□ 지원대상\n" + quote;
        assertThat(scope(body, quote)).isNull();
        assertThat(scope("[표]\n3. 점프업1000\n[/표]\n□ 신청자격\n" + quote, quote)).isNull();
        assertThat(scope("목차\n1. 점프업100 .... 3\n\n3 점프업1000\n3-1 사업개요\n□ 신청자격\n" + quote, quote))
                .isEqualTo("점프업1000");
    }

    @Test void zipDocumentBoundariesResetInheritedTracks() {
        String body = "[파일: 첫 공고.hwp]\n1. 점프업100\n□ 지원대상\n매출액 100억원 이상\n"
                + "[파일: 둘째 공고.hwp]\n□ 지원대상\n" + quote;
        assertThat(scope(body, quote)).isNull();
    }

    @Test void identicalEvidenceAcrossTracksIsCombinedWithoutInventingCommon() {
        String body = "1. 점프업100\n□ 지원대상\n" + quote + "\n"
                + "2. 점프업500\n□ 지원대상\n" + quote + "\n";
        assertThat(scope(body, quote)).isEqualTo("점프업100 / 점프업500");
        String tracks = "스케일업100억 트랙\n□ 지원대상\n" + quote + "\n"
                + "스케일업500억 트랙\n□ 지원대상\n" + quote + "\n";
        assertThat(scope(tracks, quote)).isEqualTo("스케일업100억 트랙 / 스케일업500억 트랙");
    }

    @Test void partialUnknownLocationsAreExplicitRatherThanAssignedToFirstTrack() {
        String body = "[파일: 첫 공고.hwp]\n1. 점프업100\n□ 지원대상\n" + quote + "\n"
                + "[파일: 둘째 공고.hwp]\n□ 지원대상\n" + quote + "\n";
        assertThat(scope(body, quote)).isEqualTo("점프업100 / 적용 범위 확인 필요");
    }

    @Test void realPolicyLoanSplitAndInlineSubsectionsKeepTheFundTitle() throws Exception {
        for (String file : List.of("policy-loan-hwpx.txt", "policy-loan-pdf.txt")) {
            String body = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/participation-conditions", file));
            var sources = ParticipationRawSources.collectWithScopes(body, List.of());
            var source = sources.rules().stream().flatMap(rule -> rule.sources().stream())
                    .filter(s -> s.excerpt().replaceAll("\\s+", "").contains("업력무관제조업영위소공인")).findFirst().orElseThrow();
            assertThat(sources.scopes().scope(List.of(source), "업력 무관 제조업 영위 소공인")).contains("소공인특화자금");
        }
    }

    @Test void previousRequestRemainsFrozenWhileNewRequestIncludesProgramContext() {
        String body = "3 점프업1000\n3-1. 사업개요\n□ 지원대상\n" + quote;
        var candidates = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var oldInput = ParticipationRewriteProjection.input(candidates.guide(), false);
        var newInput = ParticipationRewriteProjection.previousInput(candidates.guide(), false, candidates.scopes());
        assertThat(ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes()).request().sources())
                .anyMatch(source -> source.groupLabel().contains("[적용 유형] 점프업1000"));
        var mapper = new ObjectMapper();
        assertThat(mapper.writeValueAsString(newInput.request())).isEqualTo(mapper.writeValueAsString(oldInput.request()));
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            String id = newInput.request().sources().stream().filter(source -> source.excerpt().contains(quote)).findFirst().orElseThrow().id();
            var saved = new Response(List.of(new Statement(List.of(id), "BUSINESS_AGE", quote, "", List.of(quote), "ELIGIBILITY")), List.of(), "READY");
            var version = DocumentVersion.create(null, 1, "공고", body, "a".repeat(64), null, 0, null);
            ReflectionTestUtils.setField(version, "id", 3L);
            String key = ReflectionTestUtils.invokeMethod(service, "fingerprint", oldInput.request(), "participation-seven-fields-v1");
            ReflectionTestUtils.setField(version, "participationRewriteKey", key);
            ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(saved));
            var guide = service.resolve(version, candidates.guide(), false, candidates.scopes());
            assertThat(guide.rewriteStatus()).isEqualTo("READY");
            assertThat(guide.categories().stream().flatMap(c -> c.rows().stream()).flatMap(r -> r.entries().stream())
                    .filter(e -> "VERIFIED".equals(e.displayStatus()))).singleElement().satisfies(entry -> {
                        assertThat(entry.rule().text()).isEqualTo(quote);
                        assertThat(entry.rule().scope()).isEqualTo("점프업1000");
                    });
            ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(Response.unavailable(oldInput.request())));
            assertThat(service.resolve(version, candidates.guide(), false, candidates.scopes()).rewriteStatus()).isEqualTo("UNAVAILABLE");
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void duplicateSavedStatementsMergeTheirScopesAndSources() {
        String body = "1. 점프업100\n□ 지원대상\n" + quote + "\n※ 첫 유형 설명\n"
                + "2. 점프업500\n□ 지원대상\n" + quote + "\n※ 둘째 유형 설명\n";
        var candidates = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        var statements = input.request().sources().stream().filter(s -> s.excerpt().contains(quote))
                .map(s -> new Statement(List.of(s.id()), "BUSINESS_AGE", quote, "", List.of(quote), "ELIGIBILITY")).toList();
        var guide = ParticipationRewriteProjection.guide(candidates.guide(), input, new Response(statements, List.of(), "READY"), "READY");
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.scope()).isEqualTo("점프업100 / 점프업500");
            assertThat(rule.sources()).hasSize(2);
        });
    }
    @Test void addingTheDisplayIndexPreservesEveryOriginalSourceAndItsOrder() {
        String body = "3 점프업1000\n3-1. 사업개요\n□ 지원대상\n" + quote;
        var original = new java.util.ArrayList<ParticipationRule>();
        ReflectionTestUtils.invokeMethod(ParticipationRawSources.class, "collect", body, null, original);
        var current = ParticipationRawSources.collectWithScopes(body, List.of());
        assertThat(current.rules()).isEqualTo(original);
    }

    @Test void whitespaceVariantsKeepBothTrackLabelsWhenStatementsAreMerged() {
        String a = "매출액100억원이상인기업";
        String b = "매출액 100억원 이상인 기업";
        String body = "1. 점프업100\n□ 지원대상\n" + a + "\n"
                + "2. 점프업500\n□ 지원대상\n" + b + "\n";
        var candidates = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        var statements = input.request().sources().stream().filter(source -> source.excerpt().contains("매출액"))
                .map(source -> {
                    String text = source.excerpt().contains(a) ? a : b;
                    return new Statement(List.of(source.id()), "REVENUE", text, "", List.of(text), "ELIGIBILITY");
                }).toList();
        var guide = ParticipationRewriteProjection.guide(candidates.guide(), input, new Response(statements, List.of(), "READY"), "READY");
        assertThat(guide.rules()).allMatch(rule -> rule.scope().contains("점프업"));
        assertThat(guide.rules().stream().map(rule -> rule.scope()).toList().toString()).contains("점프업100", "점프업500");
    }


    @Test void restartedSubsectionNumberingKeepsTheProgramTitle() {
        for (String title : List.of("지역기업 육성(성장형)", "기업역량지원", "산업 전환패키지")) {
            assertThat(scope("2\n" + title + "\n1\n사업개요\n□ 지원대상\n" + quote, quote)).isEqualTo(title);
        }
    }

    @Test void sameConditionInDifferentProgramsKeepsBothProgramLabelsInTheNewRequest() {
        String body = "1. 성장형\n□ 지원대상\n" + quote + "\n2. 확장형\n□ 지원대상\n" + quote + "\n";
        var c = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var current = ParticipationRewriteProjection.input(c.guide(), false, c.scopes());
        assertThat(current.request().sources().stream().filter(s -> s.excerpt().contains(quote)))
                .extracting(Source::groupLabel).anyMatch(s -> s.contains("성장형") && s.contains("확장형"));
    }

    @Test void recipientRolesRequireDocumentContextAndDoNotHideBuyerRecruitment() {
        String clause = "□ 신청자격\n공공기관만 신청 가능";
        for (boolean buyerNotice : List.of(false, true)) {
            String intro = buyerNotice ? "구매기관 모집 공고\n기업 제품의 판로 지원" : "중소기업 제품의 공공판로 개척을 지원하는 공고";
            String body = intro + "\n4\n구매기관 현황 및 신청\n" + clause;
            var c = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
            var evidence = c.guide().rules().stream().flatMap(r -> r.sources().stream())
                    .filter(src -> src.excerpt().contains("공공기관만")).toList();
            assertThat(evidence).isNotEmpty();
            assertThat(ParticipationAudience.recipient(evidence, c.scopes().context(evidence))).isEqualTo(!buyerNotice);
        }
    }

    @Test void operatorBeneficiaryIsHiddenFromOldCachedResponseWithoutAnotherModelCall() {
        String body = "본 모집 공고는 창업기업을 지원할 주관기관을 모집하는 공고문이며, 창업기업 모집은 별도 공고 예정\n"
                + "2\n신청자격 및 제외대상\n□ 신청 자격\n전문 기관\n3\n사업 개요\n□ 지원대상 : 업력 10년 이내 기업";
        var c = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var old = ParticipationRewriteProjection.previousInput(c.guide(), false, c.scopes());
        String id = old.request().sources().stream().filter(src -> src.excerpt().contains("업력 10년")).findFirst().orElseThrow().id();
        var saved = new Response(List.of(new Statement(List.of(id), "BUSINESS_AGE", "업력이 10년 이내인 기업이 지원 대상입니다.", "",
                List.of("업력 10년 이내 기업"), "ELIGIBILITY")), List.of(), "READY");
        var result = ParticipationRewriteProjection.guide(c.guide(), old, saved, "READY");
        assertThat(result.rules()).noneMatch(rule -> rule.text().contains("업력 10년"));
        assertThat(result.rules()).anyMatch(rule -> rule.text().contains("전문 기관"));
    }
}
