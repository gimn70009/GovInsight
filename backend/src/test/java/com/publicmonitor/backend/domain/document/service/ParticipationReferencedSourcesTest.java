package com.publicmonitor.backend.domain.document.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ParticipationReferencedSourcesTest {
    @Test void keepsFootnotesWithinApplicantBlockWithoutChangingFrozenRequest() {
        String body = "□ 신청자격\n다음 요건 모두 충족\n① 직전 기간1) 매출 50% 또는 연구개발비2) 3%\n"
                + "1) 전년도 4분기부터 올해 3분기\n2) 별표 3에 따른 연구개발비\n② 평가 80점 이상\n□ 지원규모\n40개 기업";
        var data = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        var outgoing = ParticipationSourceSelection.select(input).outbound(input);
        assertThat(input.request().sources()).noneMatch(s -> s.excerpt().contains("직전 기간1)") && s.excerpt().contains("1) 전년도"));
        assertThat(outgoing.sources()).singleElement().satisfies(s -> {
            assertThat(s.id()).startsWith("u");
            assertThat(s.excerpt()).contains("1) 전년도", "2) 별표", "② 평가", "또는").doesNotContain("40개 기업");
            assertThat(body).contains(s.excerpt());
        });
    }

    @Test void linksOnlySameRegulationAppendixAndPreservesAllSubsections() {
        String body = "3. 신청요건 : 「교육 운영 지침」 별표(교육기관 기준)를 충족하는 기관\n4. 신청방법\n온라인 신청\n"
                + "별지 1 신청서\n사업자명 빈칸\n■ 교육 운영 지침 [별표]\n교육기관 기준\n1. 시설\n"
                + "가. 실습시설\n교육장 400㎡ 이상, 온라인 전용은 예외\n나. 인력\n각 분야 1명 또는 양쪽 자격을 가진 1명\n"
                + "2. 운영\n교육계획 수립\n별지 2 동의서\n동의합니다.";
        var data = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources())
                .anySatisfy(s -> assertThat(s.excerpt()).contains("400㎡", "나. 인력", "2. 운영", "예외").doesNotContain("동의합니다", "사업자명"));
        assertThat(ParticipationReferencedSources.collect(body.replace("■ 교육 운영 지침", "■ 다른 지침"), null)).isEmpty();
    }

    @Test void doesNotTreatNumberedSectionsOrUnreferencedAppendicesAsFootnotes() {
        String body = "□ 신청자격\n기관만 신청 가능\n1) 제출서류\n서약서\n□ 기타\n[별표 1]\n무관한 기준";
        assertThat(ParticipationReferencedSources.collect(body, null)).isEmpty();
    }
    @Test void ignoresQualificationExamplesInsideFormsAndStopsAtForms() {
        String form = "별지 1 신청서\n□ 신청자격\n기간1) 내 기업\n1) 지난해\n";
        assertThat(ParticipationReferencedSources.collect(form, "notice.pdf")).isEmpty();
        var sources = ParticipationReferencedSources.collect("□ 신청자격\n기간1) 내 기업\n1) 올해\n" + form, "notice.pdf");
        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.attachmentName()).isEqualTo("notice.pdf");
            assertThat(source.excerpt()).contains("올해").doesNotContain("지난해", "별지");
        });
    }

    @Test void linksNumberedReferenceInBodyUnderStandaloneHeading() {
        String notice = "□ 신청자격\nㅇ 「지역기관 운영규정」 별표 2의 기준을 갖춘 기관\n□ 지원규모\n3개 기관\n";
        String appendix = "■ 지역기관 운영규정 [별표 2]\n1. 시설\n회의실과 실습실 확보\n";
        assertThat(ParticipationReferencedSources.collect(notice + appendix, "notice.hwpx"))
                .singleElement().satisfies(source -> assertThat(source.excerpt()).contains("회의실과 실습실"));
        // References never cross documents, even if another attachment uses the same number.
        assertThat(ParticipationReferencedSources.collect(notice, "notice.hwpx")).isEmpty();
        assertThat(ParticipationReferencedSources.collect(appendix, "other.hwpx")).isEmpty();
    }

}
