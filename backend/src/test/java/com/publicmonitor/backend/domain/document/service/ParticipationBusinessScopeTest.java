package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ParticipationBusinessScopeTest {
    private final String quote = "협회·단체, 연구기관, 기업지원기관이 단독 또는 컨소시엄으로 신청할 수 있습니다.";

    @Test void cachedSectionTitlesAreRemovedWithoutChangingEvidenceOrMakingPaidCalls() {
        var candidates = ParticipationConditionResolver.candidatesWithScopes("□ 신청자격\n" + quote, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        var source = input.request().sources().stream().filter(s -> s.excerpt().contains(quote)).findFirst().orElseThrow();
        var mapper = new ObjectMapper();
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            for (String label : List.of("아. 세부과제 지원유형", "2. 사업 유형", "① 지원대상", "(가) 신청자격",
                    "3-1. 사업개요", "유형별 지원내용", "내역사업 구분", "신청자격 및 선정기준",
                    "사업 개요 - 지원분야", "사업 개요 - 수행내용", "사업개요>지원분야",
                    "총괄주관연구개발기관 모집 공고 / 자격 확인서")) {
                var saved = new Response(List.of(new Statement(List.of(source.id()), "BUSINESS_FORM", quote, "",
                        List.of(quote), "ELIGIBILITY", label, "총괄주관기관", List.of())), List.of(), "READY");
                var version = DocumentVersion.create(null, 1, "공고", quote, "b".repeat(64), null, 0, null);
                ReflectionTestUtils.setField(version, "id", 15L);
                ReflectionTestUtils.setField(version, "participationRewriteKey", service.fingerprint(input.request()));
                String json = mapper.writeValueAsString(saved);
                ReflectionTestUtils.setField(version, "participationRewriteJson", json);
                var guide = service.resolve(version, candidates.guide(), false, candidates.scopes());
                assertThat(guide.rewriteStatus()).isEqualTo("READY");
                assertThat(guide.rules()).singleElement().satisfies(rule -> {
                    assertThat(rule.scope()).as(label).isNull();
                    // The model's role is absent from this citation; keep the condition, not its invented badge.
                    assertThat(rule.audience()).isNull();
                    assertThat(rule.text()).isEqualTo(quote);
                    assertThat(rule.sources()).anySatisfy(evidence -> assertThat(evidence.excerpt()).contains(quote));
                });
                assertThat(ReflectionTestUtils.getField(version, "participationRewriteJson")).isEqualTo(json);
            }
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void actualTableTypesAndExplicitUnknownScopesRemainDistinct() {
        String table = "아. 세부과제 지원유형\n[표]\n구분\t일반 유형\tDX 유형\n지원대상\t" + quote + "\t" + quote + "\n[/표]";
        var candidates = ParticipationConditionResolver.candidatesWithScopes(table, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        var source = input.availableSources().stream().filter(s -> s.excerpt().contains(quote)).findFirst().orElseThrow();
        String[][] scopes = {
                {"일반 유형 / DX 유형", "일반 유형 / DX 유형"},
                {"① 점프업100", "① 점프업100"},
                {"스마트공장 구축 · 자율형공장", "스마트공장 구축 · 자율형공장"},
                {"소재·부품·장비", "소재·부품·장비"},
                {"성장형 · 2. 신청자격 / 일반형", "성장형 / 일반형"},
                {"아. 세부과제 지원유형 / 적용 범위 확인 필요", "적용 범위 확인 필요"},
                {"공통", "공통"},
                {" common ", "공통"},
                {"COMMON", "공통"},
                {"성장사업 · Common", "성장사업 · 공통"},
                {"Common Ground 지원사업", "Common Ground 지원사업"},
                {"공급기업 역량진단", "공급기업 역량진단"},
                {"성장사업 · 사업 개요 - 지원분야 / 도약사업", "성장사업 / 도약사업"},
                {"연구기관 역량강화 지원사업", "연구기관 역량강화 지원사업"},
                {"공급기업", null}, // A bare actor is not one of this table's named business types.
                {"큐레이터", "큐레이터"},
                {null, null}
        };
        for (String[] scope : scopes) {
            var saved = new Response(List.of(new Statement(List.of(source.id()), "BUSINESS_FORM", quote, "",
                    List.of(quote), "ELIGIBILITY", scope[0], null, List.of())), List.of(), "READY");
            var guide = ParticipationRewriteProjection.guide(candidates.guide(), input, saved, "READY");
            assertThat(guide.rules()).singleElement().satisfies(rule -> {
                assertThat(rule.scope()).isEqualTo(scope[1]);
                assertThat(rule.text()).isEqualTo(quote);
            });
        }
    }
}
