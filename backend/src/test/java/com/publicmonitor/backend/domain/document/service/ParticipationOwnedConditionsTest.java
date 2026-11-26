package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ParticipationOwnedConditionsTest {
    private static final String A = "지역 창업센터", B = "기술 창업센터";
    private static final String STAFF = "◦ (인력) 센터장 1인과 매니저 1인 이상 구성";
    private static final String NOTE = "별도의 상근 행정매니저를 두는 센터의 총괄매니저는 상근 겸직 가능";
    private static final String MANAGER = "총괄매니저\t총괄매니저\t상근 근무자로 아래 자격 필요 ⏎ * " + NOTE;
    private static final String QUALIFICATION = "\t자격요건\t전문자격 또는 관련 기관 근무 5년 이상 중 하나 이상";
    private static final String SEATS_A = "1인 기업이 사용할 입주공간 15석 이상";
    private static final String SEATS_B = "예비창업자 10팀 이상이 사용할 입주공간";

    @Test void cachedModelCategoriesAndDetailsRemainUnchangedWithoutPaidCallsOrWrites() {
        String table = "□ 신청요건\n" + STAFF + "\n[표]\n" + MANAGER + "\n" + QUALIFICATION
                + "\n입주공간\t" + A + "\t" + SEATS_A + "\n입주공간\t" + B + "\t" + SEATS_B + "\n[/표]";
        var candidates = ParticipationConditionResolver.candidatesWithScopes(table, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        var source = input.availableSources().stream().filter(s -> s.excerpt().contains(MANAGER)).findFirst().orElseThrow();
        var saved = new Response(List.of(
                new Statement(List.of(source.id()), "BUSINESS_FORM", "센터장과 매니저를 구성해야 합니다.", "", List.of(STAFF), "ELIGIBILITY", A + "‧" + B, null),
                new Statement(List.of(source.id()), "EXPERIENCE", "총괄매니저는 자격 중 하나 이상을 충족해야 합니다.", "", List.of(MANAGER + "\n" + QUALIFICATION), "ELIGIBILITY", "총괄매니저", null,
                        List.of(new Detail(List.of(source.id()), "전문자격 또는 관련 기관 근무 경력이 있어야 합니다.", List.of(QUALIFICATION)))),
                new Statement(List.of(source.id()), "EXPERIENCE", "15석 이상을 갖추어야 합니다.", "", List.of(SEATS_A), "ELIGIBILITY", A, null),
                new Statement(List.of(source.id()), "EXPERIENCE", "10팀 이상의 공간을 갖추어야 합니다.", "", List.of(SEATS_B), "ELIGIBILITY", B, null)
        ), List.of(), "READY");
        var mapper = new ObjectMapper();
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = DocumentVersion.create(null, 1, "공고", table, "d".repeat(64), null, 0, null);
            ReflectionTestUtils.setField(version, "id", 33L);
            String key = service.fingerprint(input.request()), json = mapper.writeValueAsString(saved);
            ReflectionTestUtils.setField(version, "participationRewriteKey", key);
            ReflectionTestUtils.setField(version, "participationRewriteJson", json);
            var guide = service.resolve(version, candidates.guide(), false, candidates.scopes());
            assertThat(guide.rules()).hasSize(4);
            assertThat(guide.rules()).extracting(rule -> rule.label())
                    .containsExactly("신청 주체", "실적·수행 역량", "실적·수행 역량", "실적·수행 역량");
            assertThat(guide.rules()).extracting(rule -> rule.scope()).containsExactly(A + " / " + B, "총괄매니저", A, B);
            assertThat(guide.rules().get(1).details()).containsExactly(saved.statements().get(1).details().getFirst().text());
            assertThat(guide.rules().get(2).details()).isEmpty();
            assertThat(guide.rules().get(3).details()).isEmpty();
            assertThat(guide.rules().get(1).sources()).anySatisfy(s -> assertThat(s.excerpt()).contains(NOTE));
            assertThat(ReflectionTestUtils.getField(version, "participationRewriteKey")).isEqualTo(key);
            assertThat(ReflectionTestUtils.getField(version, "participationRewriteJson")).isEqualTo(json);
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void punctuationOnlySplitsCompleteIndependentlyNamedSiblingPrograms() {
        for (String join : List.of("‧", "·", "ㆍ", " 및 ", "와 ", " / "))
            assertThat(ParticipationNamedPrograms.expand(A + join + B, List.of(A, B))).isEqualTo(A + " / " + B);
        assertThat(ParticipationNamedPrograms.expand(A + " · " + B, List.of(A, B))).isEqualTo(A + " · " + B);
        assertThat(ParticipationNamedPrograms.expand("소재·부품·장비", List.of("제조", "기술"))).isEqualTo("소재·부품·장비");
        assertThat(ParticipationNamedPrograms.expand(A + "‧" + B, List.of(A))).isEqualTo(A + "‧" + B);
        assertThat(ParticipationNamedPrograms.expand("A·B·C", List.of("A", "B·C", "A·B", "C"))).isEqualTo("A·B·C");
        assertThat(ParticipationNamedPrograms.expand("공통", List.of(A, B))).isEqualTo("공통");
        assertThat(ParticipationNamedPrograms.expand(null, List.of(A, B))).isNull();
    }

    @Test void arbitraryRoleNamesDoNotChangeCategoriesOrAddUnrequestedNotes() {
        for (String role : List.of("현장책임자", "큐레이터", "작업반장", "총괄매니저")) {
            String quote = role + "\t상근 담당자 필요 ⏎ * 대체 근무 가능";
            var candidates = ParticipationConditionResolver.candidatesWithScopes(
                    "□ 신청자격\n[표]\n구분\t요건\n" + quote + "\n[/표]", List.of(), null);
            var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
            var source = input.availableSources().stream().filter(s -> s.excerpt().contains(quote)).findFirst().orElseThrow();
            var saved = new Statement(List.of(source.id()), "EXPERIENCE", role + "는 상근 담당자여야 합니다.", "",
                    List.of(quote), "ELIGIBILITY", "공통", null,
                    List.of(new Detail(List.of(source.id()), "대체 근무가 가능합니다.", List.of("대체 근무 가능"))));
            var guide = ParticipationRewriteProjection.guide(candidates.guide(), input,
                    new Response(List.of(saved), List.of(), "READY"), "READY");
            assertThat(guide.rules()).singleElement().satisfies(rule -> {
                assertThat(rule.text()).isEqualTo(saved.text());
                assertThat(rule.label()).isEqualTo("실적·수행 역량");
                assertThat(rule.scope()).isEqualTo("공통");
                assertThat(rule.details()).containsExactly(saved.details().getFirst().text());
            });
        }
    }
}
