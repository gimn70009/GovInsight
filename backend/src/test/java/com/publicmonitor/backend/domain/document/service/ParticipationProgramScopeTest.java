package com.publicmonitor.backend.domain.document.service;

import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ParticipationProgramScopeTest {
    private final String quote = "- (도입기업) 국내 중소·중견 제조기업 중 중간1 이상 스마트공장 구축기업";
    private final String body = "1 스마트공장 구축\n③ 자율형공장\n□ 사업개요\n◦ 지원 대상\n" + quote + "\n";

    @Test void savedApplicantScopeIsRepairedFromItsOwnQuotationWithoutPaidCallsOrCacheWrites() {
        var data = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        var source = input.availableSources().stream().filter(s -> s.excerpt().contains(quote)).findFirst().orElseThrow();
        var saved = new Response(List.of(new Statement(List.of(source.id()), "BUSINESS_FORM",
                "도입기업은 중간1 이상 스마트공장 구축기업이어야 합니다.", "", List.of(quote), "ELIGIBILITY", "제조기업", null)), List.of(), "READY");
        var mapper = new ObjectMapper();
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = DocumentVersion.create(null, 1, "통합공고", body, "a".repeat(64), null, 0, null);
            ReflectionTestUtils.setField(version, "id", 6L);
            String key = service.fingerprint(input.request()), json = mapper.writeValueAsString(saved);
            ReflectionTestUtils.setField(version, "participationRewriteKey", key);
            ReflectionTestUtils.setField(version, "participationRewriteJson", json);
            var guide = service.resolve(version, data.guide(), false, data.scopes());
            assertThat(guide.rules()).singleElement().satisfies(rule -> {
                assertThat(rule.scope()).isEqualTo("스마트공장 구축 · 자율형공장");
                assertThat(rule.audience()).isEqualTo("도입기업");
                assertThat(rule.text()).isEqualTo(saved.statements().getFirst().text());
                assertThat(rule.sources()).anySatisfy(s -> assertThat(s.excerpt()).contains(quote));
            });
            assertThat(version.getParticipationRewriteJson()).isEqualTo(json);
            assertThat(version.getParticipationRewriteKey()).isEqualTo(key);
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void missingScopeAndMislabelledConsortiumUseTheCitedProgramNotTheNextHeading() {
        String quote = "◦ (지원 대상) 제조솔루션 공급기업(주관) 컨소시엄";
        String text = "4 클라우드 종합솔루션\n□ 사업개요\n" + quote + "\n5 공급기업 역량진단\n□ 사업개요\n◦ (지원 대상) 등록 공급기업\n";
        for (String scope : new String[]{null, "컨소시엄"})
            assertThat(project(text, quote, scope).scope()).isEqualTo("클라우드 종합솔루션");
        assertThat(project(text, "◦ (지원 대상) 등록 공급기업", "공급기업 역량진단").scope())
                .isEqualTo("공급기업 역량진단");
    }

    @Test void explicitTableSubtypesSurviveWhileTargetColumnIsNotAProgram() {
        String row = "지원대상\t국내 제조기업\t연구기관";
        String text = "1 성장 지원사업\n□ 지원대상\n[표]\n구분\t일반형\tDX형\n" + row + "\n[/표]\n";
        assertThat(project(text, row, "일반형 / DX형").scope()).isEqualTo("일반형 / DX형");
        assertThat(project(text, row, "연구기관").scope()).isEqualTo("성장 지원사업");
        String record = "공정최적화R&D\t중소 제조기업\t10개";
        text = "1 공정혁신 지원사업\n□ 지원조건\n[표]\n지원분야\t지원대상\t지원과제\n" + record + "\n[/표]\n";
        assertThat(project(text, record, "공정최적화R&D").scope()).isEqualTo("공정최적화R&D");
        assertThat(project(text, record, "중소 제조기업").scope()).isEqualTo("공정혁신 지원사업");
    }

    @Test void anotherProgramsQualificationInDetailsBecomesItsOwnRowWithItsOwnEvidence() {
        String common = "국내 중소기업이 신청할 수 있습니다.";
        String specific = "AI 도입을 희망하는 중소기업이 신청할 수 있습니다.";
        String text = "1 공통사항\n□ 지원대상\n" + common + "\n2 제조혁신사업\n□ 지원대상\n" + specific;
        var data = ParticipationConditionResolver.candidatesWithScopes(text, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        String main = input.availableSources().stream().filter(s -> s.excerpt().contains(common)).findFirst().orElseThrow().id();
        String other = input.availableSources().stream().filter(s -> s.excerpt().contains(specific)).findFirst().orElseThrow().id();
        var detail = new Detail(List.of(other), specific, List.of(specific));
        var item = new Statement(List.of(main), "BUSINESS_FORM", common, "", List.of(common), "ELIGIBILITY", null, null, List.of(detail));
        var guide = ParticipationRewriteProjection.guide(data.guide(), input, new Response(List.of(item), List.of(), "READY"), "READY");
        assertThat(guide.rules()).extracting(ParticipationRule::scope).containsExactly("공통", "제조혁신사업");
        assertThat(guide.rules()).allSatisfy(rule -> assertThat(rule.details()).isEmpty());
        assertThat(guide.rules().get(1).sources()).singleElement().satisfies(source -> assertThat(source.excerpt()).contains(specific).doesNotContain(common));
    }

    @Test void aCachedTableQuoteIncludingTheNextCircledHeadingKeepsTheTableProgram() {
        String row = "공정최적화R&D\t중소 제조기업\t10개";
        String quoted = row + "\n[/표]\n③ 다음 로봇개발사업";
        String text = "1 공정혁신 지원사업\n□ 지원조건\n[표]\n지원분야\t지원대상\t지원과제\n"
                + quoted + "\n□ 사업개요\n◦ (지원 대상) 로봇 제조기업";
        assertThat(project(text, quoted, "중소 제조기업").scope()).isEqualTo("공정혁신 지원사업");
        assertThat(project(text, quoted, "공정최적화R&D").scope()).isEqualTo("공정최적화R&D");
    }

    @Test void unknownAndExplicitUncertaintyAreNeverAssignedToTheFirstProgram() {
        assertThat(project("□ 지원대상\n" + quote, quote, null).scope()).isNull();
        assertThat(project(body, quote, "적용 범위 확인 필요").scope()).isEqualTo("적용 범위 확인 필요");
        String duplicated = "1 성장사업\n□ 지원대상\n" + quote + "\n2 도약사업\n□ 지원대상\n" + quote + "\n";
        var data = ParticipationRawSources.collectWithScopes(duplicated, List.of());
        var sources = data.rules().stream().flatMap(r -> r.sources().stream()).filter(s -> s.excerpt().contains(quote)).distinct().toList();
        var item = new Statement(List.of("s1"), "BUSINESS_FORM", "대상 기업입니다.", "", List.of(quote), "ELIGIBILITY", "제조기업", null);
        assertThat(ParticipationProgramScope.resolve("제조기업", item, sources, data.scopes())).isEqualTo("적용 범위 확인 필요");
    }

    private ParticipationRule project(String text, String quote, String scope) {
        var data = ParticipationConditionResolver.candidatesWithScopes(text, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        var source = input.availableSources().stream().filter(s -> s.excerpt().contains(quote)).findFirst().orElseThrow();
        var item = new Statement(List.of(source.id()), "BUSINESS_FORM", "원문에 명시된 기업이 신청할 수 있습니다.", "", List.of(quote), "ELIGIBILITY", scope, null);
        return ParticipationRewriteProjection.guide(data.guide(), input, new Response(List.of(item), List.of(), "READY"), "READY").rules().getFirst();
    }
}
