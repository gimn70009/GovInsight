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
                        assertThat(entry.rule().text()).isEqualTo(quote.replace("수 있음", "수 있습니다."));
                        assertThat(entry.rule().scope()).isNull();
                    });
            ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(Response.unavailable(oldInput.request())));
            assertThat(service.resolve(version, candidates.guide(), false, candidates.scopes()).rewriteStatus()).isEqualTo("UNAVAILABLE");
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void separateSavedStatementsDoNotInferAndMergeProgramScopes() {
        String body = "1. 점프업100\n□ 지원대상\n" + quote + "\n※ 첫 유형 설명\n"
                + "2. 점프업500\n□ 지원대상\n" + quote + "\n※ 둘째 유형 설명\n";
        var candidates = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        var statements = input.request().sources().stream().filter(s -> s.excerpt().contains(quote))
                .map(s -> new Statement(List.of(s.id()), "BUSINESS_AGE", quote, "", List.of(quote), "ELIGIBILITY")).toList();
        var guide = ParticipationRewriteProjection.guide(candidates.guide(), input, new Response(statements, List.of(), "READY"), "READY");
        assertThat(guide.rules()).hasSize(2).allSatisfy(rule -> {
            assertThat(rule.scope()).isNull();
            assertThat(rule.sources()).hasSize(1);
            assertThat(rule.text()).isEqualTo(quote.replace("수 있음", "수 있습니다."));
        });
    }
    @Test void frozenParsingPreservesEveryOriginalSourceAndItsOrder() {
        String body = "3 점프업1000\n3-1. 사업개요\n□ 지원대상\n" + quote;
        var original = new java.util.ArrayList<ParticipationRule>();
        ReflectionTestUtils.invokeMethod(ParticipationRawSources.class, "collect", body, null, original, true);
        var current = ParticipationRawSources.collectWithScopes(body, List.of());
        assertThat(current.scopes().compatibility().rules()).isEqualTo(original);
        assertThat(current.rules()).flatExtracting(ParticipationRule::sources).anyMatch(source -> source.sectionTitle().contains("3-1. 사업개요"));
    }

    @Test void whitespaceVariantsDoNotCauseBackendScopeInference() {
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
        assertThat(guide.rules()).hasSize(2).allSatisfy(rule -> assertThat(rule.scope()).isNull());
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
                .extracting(Source::groupLabel).anyMatch(s -> s.contains("성장형")).anyMatch(s -> s.contains("확장형"));
    }



    @Test void savedModelClassificationIsNotOverruledByRecipientHeuristics() {
        String body = "본 모집 공고는 창업기업을 지원할 주관기관을 모집하는 공고문이며, 창업기업 모집은 별도 공고 예정\n"
                + "2\n신청자격 및 제외대상\n□ 신청 자격\n전문 기관\n3\n사업 개요\n□ 지원대상 : 업력 10년 이내 기업";
        var c = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var old = ParticipationRewriteProjection.previousInput(c.guide(), false, c.scopes());
        String id = old.request().sources().stream().filter(src -> src.excerpt().contains("업력 10년")).findFirst().orElseThrow().id();
        var saved = new Response(List.of(new Statement(List.of(id), "BUSINESS_AGE", "업력이 10년 이내인 기업이 지원 대상입니다.", "",
                List.of("업력 10년 이내 기업"), "ELIGIBILITY")), List.of(), "READY");
        var result = ParticipationRewriteProjection.guide(c.guide(), old, saved, "READY");
        assertThat(result.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.text()).isEqualTo(saved.statements().getFirst().text());
            assertThat(rule.scope()).isNull();
        });
    }

    @Test void wrappedSentenceTailIsNotADisplayTrackAndCannotReplaceItsParent() {
        for (String tail : List.of("는사업", "는 사업", "의 프로그램", "을 지원")) {
            String body = "1. 경제안보지원사업\n□ 사업개요\n안정적인 공급망 확보를 지원하\n" + tail
                    + "\n□ 신청자격\n" + quote;
            var collected = ParticipationRawSources.collectWithScopes(body, List.of());
            var evidence = collected.rules().stream().flatMap(rule -> rule.sources().stream())
                    .filter(source -> source.excerpt().contains(quote)).toList();
            // Request-side labels intentionally remain frozen for already-paid cached results.
            assertThat(collected.scopes().scope(evidence, quote)).isEqualTo(tail);
            assertThat(collected.scopes().displayScope(evidence, quote)).isEqualTo("경제안보지원사업");
        }
    }

    @Test void aWrappedTailWithoutAParentDoesNotInventAProgramName() {
        String body = "안정적인 공급망 확보를 지원하\n는사업\n□ 신청자격\n" + quote;
        var collected = ParticipationRawSources.collectWithScopes(body, List.of());
        var evidence = collected.rules().stream().flatMap(rule -> rule.sources().stream())
                .filter(source -> source.excerpt().contains(quote)).toList();
        assertThat(collected.scopes().displayScope(evidence, quote)).isNull();
    }

    @Test void legitimateProgramNamesAndDocumentBoundariesSurviveDisplayRepair() {
        for (String title : List.of("늘봄사업", "나눔사업", "함께하는사업", "으뜸기업 지원", "점프업1000")) {
            String body = "[파일: 첫 공고.hwp]\n1. 경제안보지원사업\n□ 신청자격\n다른 조건\n"
                    + "[파일: 둘째 공고.hwp]\n1. " + title + "\n□ 신청자격\n" + quote;
            var collected = ParticipationRawSources.collectWithScopes(body, List.of());
            var evidence = collected.rules().stream().flatMap(rule -> rule.sources().stream())
                    .filter(source -> source.excerpt().contains(quote)).toList();
            assertThat(collected.scopes().displayScope(evidence, quote)).isEqualTo(title);
        }
    }

    @Test void currentPaidCacheKeepsMissingScopeWithoutCallingTheModelAgain() throws Exception {
        String text = "경제안보품목 및 경제안보서비스의 원활한 도입·생산 및 제공에 기여하고자 하는 사업자 또는 사업자단체";
        String body = "1. 경제안보지원사업\n□ 사업개요\n안정적인 공급망 확보를 지원하\n는사업\n□ 지원대상\n" + text;
        var candidates = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        var source = input.request().sources().stream().filter(item -> item.excerpt().contains(text)).findFirst().orElseThrow();
        assertThat(source.groupLabel()).contains("[적용 유형] 는사업");
        var mapper = new ObjectMapper();
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var saved = new Response(List.of(new Statement(List.of(source.id()), "BUSINESS_FORM", text, "",
                    List.of(text), "ELIGIBILITY")), List.of(), "READY");
            var version = DocumentVersion.create(null, 1, "공고", body, "b".repeat(64), null, 0, null);
            ReflectionTestUtils.setField(version, "id", 12L);
            ReflectionTestUtils.setField(version, "participationRewriteKey", service.fingerprint(input.request()));
            ReflectionTestUtils.setField(version, "participationRewriteJson", mapper.writeValueAsString(saved));
            var guide = service.resolve(version, candidates.guide(), false, candidates.scopes());
            assertThat(guide.rewriteStatus()).isEqualTo("READY");
            assertThat(guide.rules()).isNotEmpty().allSatisfy(rule -> {
                assertThat(rule.scope()).isNull();
                assertThat(rule.text()).contains("경제안보품목", "사업자 또는 사업자단체");
                assertThat(rule.sources()).anySatisfy(evidence -> assertThat(evidence.excerpt()).contains(text));
            });
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    private String shownScope(String body, String quote) {
        var data = ParticipationRawSources.collectWithScopes(body, List.of());
        var evidence = data.rules().stream().flatMap(rule -> rule.sources().stream())
                .filter(source -> source.excerpt().contains(quote)).toList();
        return data.scopes().displayScope(evidence, quote);
    }

    @Test void circledProgramsKeepTheWholeTitleAndStopThePreviousProgramsScope() {
        String body = "1. 공통사항\n□ 신청자격\n중소기업\n1\n스마트공장 구축\n"
                + "② 제조AI특화\t스마트공장\t(자율형공장\tAI트랙)\n□ 사업개요\n◦ 지원 대상: AI 구축 희망 기업\n"
                + "③ 자율형공장\n□ 사업개요\n◦ 지원 대상: " + quote + "\n"
                + "④ 대중소 상생형\t스마트공장(일반형)\n□ 사업개요\n◦ 지원 대상: 국내 중소기업\n"
                + "⑤ 대중소 상생형\t스마트공장(AX선도모델)\n□ 사업개요\n◦ 지원 대상: 국내 중견기업";
        assertThat(shownScope(body, quote)).isEqualTo("스마트공장 구축 · 자율형공장");
        assertThat(shownScope(body, "AI 구축 희망 기업")).isEqualTo("스마트공장 구축 · 제조AI특화 스마트공장 (자율형공장 AI트랙)");
        assertThat(shownScope(body, "국내 중소기업")).isEqualTo("스마트공장 구축 · 대중소 상생형 스마트공장(일반형)");
        assertThat(shownScope(body, "국내 중견기업")).isEqualTo("스마트공장 구축 · 대중소 상생형 스마트공장(AX선도모델)");
    }

    @Test void pdfWordTabsAndProseEndingInSupportCannotBecomeProgramTitles() {
        String body = "5\t공급기업\t역량진단\n□ 사업개요\n◦ 지원 대상: 공급기업\n"
                + "□ 유의사항\n신청자격\t기본요건으로\t우수등급\t획득\t필요\n"
                + "기업의 현장 수요를 지원\n◦ 지원내용: 별도 안내\n"
                + "6\t제조 DX멘토단\t활용지원\n□ 사업개요\n◦ 지원 대상: 멘토 지원기업\n"
                + "□ 지원 조건\n지원\n◦ 과제별 세부 안내\n"
                + "7\t제조데이터\t상품가공\t지원\n□ 사업개요\n◦ 지원 대상: " + quote;
        assertThat(shownScope(body, "공급기업")).isEqualTo("공급기업 역량진단");
        assertThat(shownScope(body, "멘토 지원기업")).isEqualTo("제조 DX멘토단 활용지원");
        assertThat(shownScope(body, quote)).isEqualTo("제조데이터 상품가공 지원");
    }

}
