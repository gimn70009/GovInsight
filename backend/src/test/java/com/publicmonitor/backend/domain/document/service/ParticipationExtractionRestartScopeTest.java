package com.publicmonitor.backend.domain.document.service;

import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParticipationExtractionRestartScopeTest {
    @Test void fullTextRestartCannotOverwriteAValidProgramWithAnAdministrativeFragment() {
        String quote = "전담인력 7인 이상을 확보해야 합니다.";
        String body = "[PDF 16페이지 시각 판독]\n□ (제출서류) 1)사 사업\n신청서 제출\n"
                + "[문서 전체 텍스트 추출]\n지역 거점 조성 프로젝트 모집공고\n□ 신청요건\n" + quote;
        var data = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        var source = input.availableSources().stream().filter(s -> s.excerpt().contains(quote)).findFirst().orElseThrow();
        var item = new Statement(List.of(source.id()), "EXPERIENCE", quote, "", List.of(quote),
                "ELIGIBILITY", "지역 거점 조성 프로젝트", null);
        var guide = ParticipationRewriteProjection.guide(data.guide(), input,
                new Response(List.of(item), List.of(), "READY"), "READY");
        assertThat(guide.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.scope()).isEqualTo("지역 거점 조성 프로젝트");
            assertThat(rule.text()).isEqualTo(quote);
        });
    }
}
