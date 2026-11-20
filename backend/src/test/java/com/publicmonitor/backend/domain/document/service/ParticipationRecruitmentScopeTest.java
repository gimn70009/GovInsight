package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ParticipationRecruitmentScopeTest {
    private static final String CURRENT = "총괄주관연구개발기관 모집 공고\n";
    private static final String LATER = "※ 세부주관연구개발기관(기업) 모집 공고는 내년 1월 예정입니다.\n";
    private static final String QUOTE = "지원대상\t국내 공급기업 또는 수요기업";
    private static final String TABLE = "1. 사업 개요\n아. 세부과제 지원유형\n[표]\n구분\t일반 유형\n" + QUOTE + "\n[/표]\n";
    private static final String APPLICANT = "협회·단체, 연구기관, 기업지원기관이 단독 또는 컨소시엄으로 신청할 수 있습니다.";
    private static final String QUALIFICATION = "2. 신청자격\n" + APPLICANT;

    @Test void cachedReferenceIsRemovedWithoutModelCallsCacheWritesOrFingerprintChanges() {
        String body = CURRENT + LATER + TABLE + "2\n신청자격 및 선정기준\n가. 신청자격\n" + APPLICANT;
        var data = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        var reference = input.availableSources().stream().filter(s -> s.excerpt().contains(QUOTE)).findFirst().orElseThrow();
        var applicant = input.availableSources().stream().filter(s -> s.excerpt().contains(APPLICANT)).findFirst().orElseThrow();
        var saved = new Response(List.of(
                new Statement(List.of(reference.id()), "BUSINESS_FORM", "국내 공급기업 또는 수요기업이어야 합니다.", "", List.of(QUOTE), "ELIGIBILITY"),
                new Statement(List.of(applicant.id()), "BUSINESS_FORM", APPLICANT, "", List.of(APPLICANT), "ELIGIBILITY", null, null,
                        List.of(new Detail(List.of(reference.id()), "세부과제는 국내 기업이 신청할 수 있습니다.", List.of(QUOTE))))
        ), List.of(), "READY");
        var client = mock(PythonParticipationRewriteClient.class);
        var store = mock(ParticipationRewriteStore.class);
        var mapper = new ObjectMapper();
        var service = new ParticipationRewriteService(client, store, mapper);
        try {
            var version = DocumentVersion.create(null, 1, "공고", body, "d".repeat(64), null, 0, null);
            ReflectionTestUtils.setField(version, "id", 33L);
            String key = service.fingerprint(input.request()), json = mapper.writeValueAsString(saved);
            ReflectionTestUtils.setField(version, "participationRewriteKey", key);
            ReflectionTestUtils.setField(version, "participationRewriteJson", json);
            var guide = service.resolve(version, data.guide(), false, data.scopes());
            assertThat(guide.rules()).singleElement().satisfies(rule -> {
                assertThat(rule.text()).isEqualTo(APPLICANT);
                assertThat(rule.details()).isEmpty();
            });
            assertThat(ParticipationSourceSelection.select(input).outbound(input).sources())
                    .noneMatch(s -> s.id().equals(reference.id()));
            assertThat(service.fingerprint(input.request())).isEqualTo(key);
            assertThat(ReflectionTestUtils.getField(version, "participationRewriteJson")).isEqualTo(json);
            verifyNoInteractions(client, store);
        } finally { service.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "총괄주관연구개발기관 모집 공고\n",
        "총괄주관연구개발기관 및 세부주관연구개발기관 모집 공고\n※ 세부주관연구개발기관 모집은 추후 예정입니다.\n",
        "총괄주관연구개발기관 모집 공고\n※ 총괄주관연구개발기관 모집은 추후 추가 예정입니다.\n",
        "총괄주관연구개발기관 모집 공고\n※ 세부주관연구개발기관 별도 모집 예정이 아닙니다.\n",
        "연구개발사업 통합공고\n총괄주관연구개발기관 모집 공고\n※ 세부주관연구개발기관 모집은 추후 예정입니다.\n",
        "연구개발사업 통합 모집 공고\n총괄주관연구개발기관 모집 공고\n※ 세부주관연구개발기관 모집은 추후 예정입니다.\n"
    })
    void uncertainConcurrentAndIntegratedRecruitmentsKeepSubprojectTargets(String introduction) {
        var data = ParticipationConditionResolver.candidatesWithScopes(introduction + TABLE + QUALIFICATION, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources())
                .anyMatch(s -> s.excerpt().contains(QUOTE));
        var source = input.availableSources().stream().filter(s -> s.excerpt().contains(QUOTE)).findFirst().orElseThrow();
        var statement = new Statement(List.of(source.id()), "BUSINESS_FORM", "국내 기업이 신청할 수 있습니다.", "", List.of(QUOTE), "ELIGIBILITY");
        assertThat(ParticipationDisplayStatements.prepare(input, List.of(statement)))
                .anyMatch(s -> s.text().equals(statement.text()));
    }

    @Test void currentMixedSectionsAndCommonExclusionsArePreserved() {
        for (String section : List.of(
                TABLE.replace(QUOTE, QUOTE + "\n총괄주관연구개발기관\t비영리법인"),
                TABLE.replace("세부과제 지원유형", "지원제외 대상"),
                TABLE.replace("세부과제 지원유형", "신청자격"),
                TABLE + "2\n신청자격 및 선정기준\n국내 비영리법인이 신청할 수 있습니다.\n")) {
            var data = ParticipationConditionResolver.candidatesWithScopes(CURRENT + LATER + section + QUALIFICATION, List.of(), null);
            var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
            assertThat(ParticipationSourceSelection.select(input).outbound(input).sources())
                    .anyMatch(s -> s.excerpt().contains(QUOTE));
        }
    }

    @Test void separatelyNamedRolesNeedBothRecruitmentNoticeAndMatchingOriginalHeading() {
        String body = "운영기관 모집 공고\n참여기업 모집은 별도 공고 예정입니다.\n"
                + "□ 참여기업 지원대상\n지원대상: 중소기업\n□ 신청자격\n비영리기관이 신청할 수 있습니다.";
        var data = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        var reference = input.availableSources().stream().filter(s -> s.excerpt().contains("지원대상: 중소기업")).findFirst().orElseThrow();
        assertThat(ParticipationRecruitmentScope.referenceOnly(input, List.of(reference.id()))).isTrue();
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources())
                .noneMatch(s -> s.id().equals(reference.id()));
    }

    @Test void shorterSeparateRoleDoesNotMatchAnotherApplicantNamePrefix() {
        String body = "운영기관 모집 공고\n기업 모집은 별도 공고 예정입니다.\n"
                + "□ 기업지원기관 지원대상\n지원대상: 비영리기관\n□ 신청자격\n비영리법인이 신청할 수 있습니다.";
        var data = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources())
                .anyMatch(source -> source.excerpt().contains("지원대상: 비영리기관"));
    }

    @Test void admissionRestrictionIsNotARecruitmentTitle() {
        assertThat(ParticipationRecruitmentContext.from("□ 신청자격\n대학 모집제한 조치를 받은 기관은 신청할 수 없습니다.")).isEmpty();
        assertThat(ParticipationRecruitmentContext.from("□ 신청자격\n모집 제한 대상 대학은 신청할 수 없습니다.")).isEmpty();
    }
}
