package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ParticipationGeneralPolicyTest {
    private Statement statement(String text, String code, String quote) {
        return new Statement(List.of("form"), code, text, "", List.of(quote), "ELIGIBILITY");
    }

    @Test void largeCandidateCollectionReachesTheLocallyBoundedPythonSelectorOnce() throws Exception {
        var rules = new ArrayList<ParticipationRule>();
        for (int i = 0; i < 280; i++) {
            var source = new RequirementSource("ATTACHMENT", "공고.hwpx", "신청자격", null,
                    "유형 " + i + "는 중소기업이 신청할 수 있습니다.");
            rules.add(new ParticipationRule("EVIDENCE", "원문", "", null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(source)));
        }
        var raw = new ParticipationGuide(List.of(), rules, List.of(), List.of());
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        when(store.claim(any(), anyString(), any(), anyString())).thenReturn(true);
        var saved = new CountDownLatch(1);
        when(client.rewrite(any(), any())).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            assertThat(request.sources()).hasSize(280);
            assertThat(request.sources()).allSatisfy(s -> assertThat(s.groupLabel()).contains("[공고 제목] 공고"));
            return new Response(List.of(), request.sources().stream().map(Source::id).toList(), "UNAVAILABLE");
        });
        doAnswer(invocation -> { saved.countDown(); return null; }).when(store).finish(any(), anyString(), anyString());
        var service = new ParticipationRewriteService(client, store, new ObjectMapper());
        var version = DocumentVersion.create(null, 1, "공고", "중소기업", "a".repeat(64), null, 0, null);
        ReflectionTestUtils.setField(version, "id", 900L);
        try {
            assertThat(service.resolve(version, raw, false).rewriteStatus()).isEqualTo("PENDING");
            assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
            verify(client, times(1)).rewrite(any(), any());
        } finally { service.close(); }
    }

    @Test void admissionEffectIsPreservedWithoutAnActorOnTheSameLine() {
        for (String effect : List.of("선정평가 대상에서 제외", "심사 대상에서 제외", "지원 제외", "참여 제한"))
            assertThat(ParticipationSourceSelection.explicit(new RequirementSource("ATTACHMENT", "공고.pdf", "유의사항", null,
                    "다음의 경우 " + effect + "될 수 있음\n- 양식 미준수\n- 목적 불부합"))).isTrue();
    }

    @Test void nestedBracketsKeepTheFullEmbeddedDocumentNameWithoutChangingInputIdentity() {
        var source = new RequirementSource("ATTACHMENT", "첨부.zip", "기관", null, "비영리법인의 연구개발비 사용 기준");
        var rule = new ParticipationRule("EVIDENCE", "원문", "", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(source));
        var scopes = new ParticipationScopeIndex();
        scopes.addDocument("[파일: [별첨 7] 사업비 사용기준.pdf]\n" + source.excerpt(), List.of(rule));
        assertThat(scopes.documentName(source)).isEqualTo("[별첨 7] 사업비 사용기준.pdf");
        var raw = new ParticipationGuide(List.of(), List.of(rule), List.of(), List.of());
        var input = ParticipationRewriteProjection.input(raw, false, scopes);
        var before = input.request();
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources()).singleElement()
                .satisfies(s -> assertThat(s.groupLabel()).contains("[참고 규정] [별첨 7] 사업비 사용기준.pdf"));
        assertThat(input.request()).isEqualTo(before);
        assertThat(ParticipationSourceSelection.referenceDocument("사업비 사용기준 변경 공고.pdf")).isFalse();
    }

    @Test void onlyAnUnambiguousParentLawCanExpandADecreeAlias() {
        for (String law : List.of("지역혁신법", "기술진흥법")) {
            String original = "「" + law + "」 제12조 및 「" + law + "시행령」 제8조";
            assertThat(ParticipationCompleteness.expandLawAliases(original, law + " 제12조 및 시행령 제8조"))
                    .isEqualTo(law + "제12조및" + law + "시행령제8조");
            assertThat(ParticipationCompleteness.expandLawAliases(original, "다른법 제12조 및 시행령 제8조"))
                    .doesNotContain(law + "시행령");
        }
        assertThat(ParticipationCompleteness.expandLawAliases("「첫법」 제12조 및 「둘법시행령」 제8조", "첫법 제12조 및 시행령 제8조"))
                .doesNotContain("둘법시행령");
    }

    @Test void linkedNoticeExemptionOverridesAFormButNotAnotherNoticeOrConstraint() {
        var form = new Source("form", "동시수행 과제 수는 최대 4개인가?", "[공고 대상] 연구기관 모집\n[자격 확인서] 제한", List.of("BUSINESS_FORM"));
        var exemption = new Source("exception", "주관기관은 동시수행 과제 수 산정에서 제외합니다.",
                "[공고 대상] 연구기관 모집\n[확인서 적용 예외] 제한", List.of("BUSINESS_FORM"));
        var item = statement("동시수행 과제 수는 최대 4개입니다.", "BUSINESS_FORM", form.excerpt());
        assertThat(ParticipationApplicability.conflict(item, List.of(form, exemption))).isTrue();
        assertThat(ParticipationApplicability.conflict(statement("참여율은 15% 이상입니다.", "BUSINESS_FORM", form.excerpt()), List.of(form, exemption))).isFalse();
        var other = new Source("exception", exemption.excerpt(), "[공고 대상] 제조기업 모집\n[확인서 적용 예외] 제한", List.of("BUSINESS_FORM"));
        assertThat(ParticipationApplicability.conflict(item, List.of(form, other))).isFalse();
        assertThat(ParticipationApplicability.conflict(statement("공동기관의 동시수행 과제 수는 최대 4개입니다.", "BUSINESS_FORM", form.excerpt()), List.of(form, exemption))).isFalse();
        assertThat(ParticipationApplicability.conflict(statement("주관기관은 동시수행 과제 수 산정에서 제외되지 않습니다.", "BUSINESS_FORM", exemption.excerpt()), List.of(form, exemption))).isTrue();
    }

    @Test void pureAccountSetupIsGuidanceEvenInsideAMixedSourceAndContributionIsFinance() {
        String quote = "연구자는 연구성과를 등록하고 기관 대표자는 회원가입 및 전산등록을 완료해야 함";
        var source = new RequirementSource("ATTACHMENT", "공고.pdf", "접수 안내 및 자격", null, quote);
        assertThat(ParticipationPresentationQuality.procedureOnly(statement("회원가입과 기관 전산등록을 완료해야 합니다.", "BUSINESS_FORM", quote), List.of(source))).isTrue();
        assertThat(ParticipationPresentationQuality.procedureOnly(statement("회원가입과 업력 4년 이상 요건을 충족해야 합니다.", "BUSINESS_FORM", quote), List.of(source))).isFalse();
        assertThat(ParticipationApplicability.criterion(statement("자기부담금은 총사업비의 25% 이상이어야 합니다.", "REVENUE", "자기부담금"))).isEqualTo("CREDIT");
        assertThat(ParticipationApplicability.criterion(statement("매출 대비 자부담 비율을 충족해야 합니다.", "REVENUE", "매출"))).isEqualTo("REVENUE");
        assertThat(ParticipationApplicability.criterion(statement("운영인력(매니저)은 주관기관 직원으로 구성할 수 있습니다.", "BUSINESS_FORM", "운영인력"))).isEqualTo("EXPERIENCE");
        assertThat(ParticipationApplicability.criterion(statement("전문인력 양성사업의 신청 주체는 비영리기관입니다.", "BUSINESS_FORM", "비영리기관"))).isEqualTo("BUSINESS_FORM");
    }

    @Test void applicantAndRegionLabelsAreNotProgramsButAnExplicitProgramTableWins() {
        var item = statement("협의체는 신청할 수 있습니다.", "BUSINESS_FORM", "협의체");
        assertThat(ParticipationProgramScope.resolve("주관지역 / 연계지역", item, List.of(), null)).isNull();
        assertThat(ParticipationProgramScope.resolve("협의체", item, List.of(), null)).isNull();
        assertThat(ParticipationProgramScope.resolve("협의체 지원사업", item, List.of(), null)).isEqualTo("협의체 지원사업");
        var source = new RequirementSource("ATTACHMENT", "공고.pdf", "사업", null, "[표]\n사업명\t지원대상\n협의체\t기관\n[/표]");
        assertThat(ParticipationProgramScope.resolve("협의체", item, List.of(source), null)).isEqualTo("협의체");
    }
}
