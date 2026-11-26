package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ParticipationPersonnelAudienceTest {
    private static String row(String role) { return role + "\t" + role + "\t관련 업무를 수행하는 상근 담당자 필요"; }
    private static String table(String role) { return "□ 신청요건\n< 공통 신청요건(인력) >\n[표]\n" + row(role) + "\n[/표]"; }
    private static Statement statement(String id, String role) {
        return new Statement(List.of(id), "EXPERIENCE", role + "를 배치해야 합니다.", "",
                List.of(row(role)), "ELIGIBILITY", "공통", role);
    }
    private static RequirementSource source(String text) {
        return new RequirementSource("NOTICE_BODY", null, "신청요건", null, text);
    }

    @Test void roleNamesAreNotAFilterWithoutCitedPersonnelContext() {
        for (String role : List.of("총괄매니저", "큐레이터", "현장책임자")) {
            var item = statement("s0", role);
            assertThat(ParticipationApplicantLabel.isPersonnelAudience(item, List.of(source(table(role))))).isTrue();
            assertThat(ParticipationApplicantLabel.isPersonnelAudience(item,
                    List.of(source(table(role).replace("(인력)", "(신청자격)"))))).isFalse();
            assertThat(ParticipationApplicantLabel.isPersonnelAudience(item,
                    List.of(source(table(role).replace(row(role), "다른 역할\t다른 조건"))))).isFalse();
            String explicitApplicant = "< 신청요건(인력) >\n[표]\n신청구분\t자격요건\n" + row(role) + "\n[/표]";
            assertThat(ParticipationApplicantLabel.isPersonnelAudience(item, List.of(source(explicitApplicant)))).isFalse();
        }
    }

    @Test void neighboringPersonnelHeadingCannotChangeFacilityOrUncitedAudience() {
        String text = table("책임자") + "\n< 신청요건(시설) >\n[표]\n시설보유기관\t전용공간 확보\n[/표]";
        var item = new Statement(List.of("s0"), "EXPERIENCE", "전용공간을 확보해야 합니다.", "",
                List.of("시설보유기관\t전용공간 확보"), "ELIGIBILITY", "공통", "시설보유기관");
        assertThat(ParticipationApplicantLabel.isPersonnelAudience(item, List.of(source(text)))).isFalse();
    }

    @Test void cachedPersonnelBadgeIsHiddenWithoutChangingScopeTextEvidenceOrCache() {
        String role = "총괄매니저", original = table(role);
        var candidates = ParticipationConditionResolver.candidatesWithScopes(original, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        var selected = input.availableSources().stream().filter(s -> s.excerpt().contains(row(role))).findFirst().orElseThrow();
        var saved = new Response(List.of(statement(selected.id(), role)), List.of(), "READY");
        var mapper = new ObjectMapper();
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = DocumentVersion.create(null, 1, "공고", original, "e".repeat(64), null, 0, null);
            ReflectionTestUtils.setField(version, "id", 41L);
            String key = service.fingerprint(input.request()), json = mapper.writeValueAsString(saved);
            ReflectionTestUtils.setField(version, "participationRewriteKey", key);
            ReflectionTestUtils.setField(version, "participationRewriteJson", json);
            var guide = service.resolve(version, candidates.guide(), false, candidates.scopes());
            assertThat(guide.rules()).singleElement().satisfies(rule -> {
                assertThat(rule.audience()).isNull();
                assertThat(rule.scope()).isEqualTo("공통");
                assertThat(rule.text()).isEqualTo(saved.statements().getFirst().text());
                assertThat(rule.sources()).containsAll(input.evidence().get(selected.id()));
            });
            assertThat(ReflectionTestUtils.getField(version, "participationRewriteJson")).isEqualTo(json);
            assertThat(ReflectionTestUtils.getField(version, "participationRewriteKey")).isEqualTo(key);
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }
}
