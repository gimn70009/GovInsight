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

class ParticipationApplicantLabelTest {
    private static final List<String> LABELS = List.of("협회·단체", "연구기관", "기업지원기관");
    private static final List<String> ROWS = List.of(
            "협회·단체\t업종별 대표성을 보유한 비영리법인으로서 주무부처에 등록된 협회 또는 단체",
            "연구기관\t국공립연구기관 및 법령에 따라 설립된 산업기술 분야의 연구기관",
            "기업지원기관\t공통운영요령 제3조 사업을 수행하는 비영리기관(연구기관 및 대학 제외)");
    private static final String TABLE = "가. 신청자격\n[표]\n구 분\t자 격\n" + String.join("\n", ROWS) + "\n[/표]";

    private RequirementSource source(String text) {
        return new RequirementSource("NOTICE_BODY", null, "가. 신청자격", null, text);
    }

    private Statement statement(String quote) {
        return new Statement(List.of("s0"), "BUSINESS_FORM", "신청 자격을 충족해야 합니다.", "",
                List.of(quote), "ELIGIBILITY", null, null);
    }

    @Test void cachedQualificationsRecoverSeparateApplicantLabelsWithoutPaidCallsOrCacheWrites() {
        var candidates = ParticipationConditionResolver.candidatesWithScopes(TABLE, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        var original = input.availableSources().stream().filter(s -> s.excerpt().contains(ROWS.getFirst())).findFirst().orElseThrow();
        var saved = new Response(java.util.stream.IntStream.range(0, 3).mapToObj(i -> new Statement(
                List.of(original.id()), "BUSINESS_FORM", LABELS.get(i) + "은 신청 자격을 충족해야 합니다.", "",
                List.of(ROWS.get(i)), "ELIGIBILITY", i == 0 ? "협회·단체" : i == 1 ? "실제 사업" : null, null)).toList(), List.of(), "READY");
        var mapper = new ObjectMapper();
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = DocumentVersion.create(null, 1, "공고", TABLE, "c".repeat(64), null, 0, null);
            ReflectionTestUtils.setField(version, "id", 15L);
            String key = service.fingerprint(input.request());
            String json = mapper.writeValueAsString(saved);
            ReflectionTestUtils.setField(version, "participationRewriteKey", key);
            ReflectionTestUtils.setField(version, "participationRewriteJson", json);
            var guide = service.resolve(version, candidates.guide(), false, candidates.scopes());
            assertThat(guide.rules()).extracting(r -> r.audience()).containsExactlyElementsOf(LABELS);
            assertThat(guide.rules()).extracting(r -> r.scope()).containsExactly(null, "실제 사업", null);
            for (int i = 0; i < 3; i++) {
                assertThat(guide.rules().get(i).text()).isEqualTo(saved.statements().get(i).text());
                assertThat(guide.rules().get(i).sources()).containsAll(input.evidence().get(original.id()));
            }
            assertThat(ReflectionTestUtils.getField(version, "participationRewriteKey")).isEqualTo(key);
            assertThat(ReflectionTestUtils.getField(version, "participationRewriteJson")).isEqualTo(json);
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @Test void bodyOnlyQuotesRecoverTheirOwnRowRatherThanOtherEntitiesMentionedInsideIt() {
        for (int i = 0; i < 3; i++)
            assertThat(ParticipationApplicantLabel.from(statement(ROWS.get(i).split("\t")[1]), List.of(source(TABLE))))
                    .isEqualTo(LABELS.get(i));
        String reversed = "[표]\n자격\t신청 주체\n대학의 산학협력 활동을 수행하는 기관\t산학협력단\n[/표]";
        assertThat(ParticipationApplicantLabel.from(statement("대학의 산학협력 활동을 수행하는 기관"), List.of(source(reversed))))
                .isEqualTo("산학협력단");
    }

    @Test void ambiguousOrUnstructuredEvidenceCannotInventAnApplicantLabel() {
        String common = "업종별 대표성을 보유한 비영리법인";
        String duplicate = "[표]\n구분\t자격\n협회\t" + common + "\n단체\t" + common + "\n[/표]";
        assertThat(ParticipationApplicantLabel.from(statement(common), List.of(source(duplicate)))).isNull();
        assertThat(ParticipationApplicantLabel.from(statement(ROWS.get(0) + "\n" + ROWS.get(1)), List.of(source(TABLE)))).isNull();
        var twoQuotes = new Statement(List.of("s0"), "BUSINESS_FORM", "조건입니다.", "", List.of(ROWS.get(0), ROWS.get(1)), "ELIGIBILITY");
        assertThat(ParticipationApplicantLabel.from(twoQuotes, List.of(source(TABLE)))).isNull();
        assertThat(ParticipationApplicantLabel.from(statement(ROWS.get(0)), List.of(source(ROWS.get(0))))).isNull();
    }

    @Test void programsAndBudgetTablesAreNotApplicantCategories() {
        String body = "중소기업이며 해당 신청 자격을 충족해야 합니다.";
        for (String table : List.of(
                "[표]\n구분\t일반 유형\tDX 유형\n지원대상\t" + body + "\t" + body + "\n[/표]",
                "[표]\n구분\t자격\n일반 유형\t" + body + "\n[/표]",
                "[표]\n구분\t지원금\n연구기관\t" + body + "\n[/표]"))
            assertThat(ParticipationApplicantLabel.from(statement(body), List.of(source(table)))).isNull();
    }

    @Test void explicitAudienceAndExclusionMeaningRemainUntouched() {
        var candidates = ParticipationConditionResolver.candidatesWithScopes(TABLE, List.of(), null);
        var input = ParticipationRewriteProjection.input(candidates.guide(), false, candidates.scopes());
        var original = input.availableSources().stream().filter(s -> s.excerpt().contains(ROWS.getFirst())).findFirst().orElseThrow();
        var item = new Statement(List.of(original.id()), "BUSINESS_FORM", "협회는 신청할 수 있습니다.", "",
                List.of(ROWS.getFirst()), "ELIGIBILITY", null, "협회·단체(주관기관)");
        var guide = ParticipationRewriteProjection.guide(candidates.guide(), input, new Response(List.of(item), List.of(), "READY"), "READY");
        assertThat(guide.rules().getFirst().audience()).isEqualTo("협회·단체(주관기관)");
        var excluded = new Statement(List.of("s0"), "BUSINESS_FORM", "제외됩니다.", "", List.of(ROWS.get(2)), "EXCLUSION");
        assertThat(ParticipationApplicantLabel.from(excluded, List.of(source(TABLE)))).isNull();
    }
}
