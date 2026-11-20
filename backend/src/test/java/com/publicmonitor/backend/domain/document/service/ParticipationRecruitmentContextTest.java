package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.entity.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParticipationRecruitmentContextTest {
    private final String introduction = "총괄주관연구개발기관 모집 공고\n"
            + "※ 세부주관연구개발기관(기업) 모집 공고는 내년 1월 예정입니다.\n";
    private final String body = introduction + "1. 사업 개요\n아. 세부과제 지원유형\n[표]\n"
            + "구분\t일반 유형\tDX 유형\n지원대상\t국내 공급기업\t국내 수요기업\n[/표]\n"
            + "2. 신청자격\n협회·단체, 연구기관, 기업지원기관이 신청할 수 있습니다.";

    @Test void currentAndLaterRecruitmentReachNewRequestsWithoutChangingFrozenSourceIds() {
        var data = ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        assertThat(input.request().sources()).noneMatch(s -> s.groupLabel().contains("[공고 대상]"));
        var outbound = ParticipationSourceSelection.select(input).outbound(input);
        assertThat(outbound.sources()).isNotEmpty().allSatisfy(source -> {
            assertThat(source.groupLabel()).contains("[공고 대상] 총괄주관연구개발기관 모집 공고",
                    "세부주관연구개발기관(기업) 모집 공고는 내년 1월 예정입니다.");
            assertThat(input.evidence()).containsKey(source.id());
            assertThat(input.evidence().get(source.id())).anySatisfy(original ->
                    assertThat(source.excerpt()).isEqualTo(original.excerpt()));
        });
        assertThat(input.availableSources()).anyMatch(s -> s.excerpt().contains("일반 유형\tDX 유형"));
        assertThat(outbound.sources()).noneMatch(s -> s.excerpt().contains("일반 유형\tDX 유형"));
        assertThat(input.request().sources()).noneMatch(s -> s.groupLabel().contains("[공고 대상]"));
    }

    @Test void recruitmentContextNeverLeaksAcrossAttachments() {
        var notice = DocumentAttachment.create(null, "기관 모집 공고.hwpx", null, null, null, null, null,
                body, AttachmentParseStatus.COMPLETED, null);
        var other = DocumentAttachment.create(null, "기업 공고.hwpx", null, null, null, null, null,
                "성장기업 모집 공고\n□ 신청자격\n업력 7년 이내 기업", AttachmentParseStatus.COMPLETED, null);
        var data = ParticipationConditionResolver.candidatesWithScopes("", List.of(notice, other), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        var outbound = ParticipationSourceSelection.select(input).outbound(input);
        assertThat(outbound.sources()).filteredOn(s -> s.excerpt().contains("업력 7년")).isNotEmpty()
                .allSatisfy(s -> assertThat(s.groupLabel()).contains("성장기업 모집 공고").doesNotContain("총괄주관", "세부주관"));
    }

    @Test void missingContextDoesNotInventAnApplicantRole() {
        assertThat(ParticipationRecruitmentContext.from("사업 개요\n지원대상: 국내 중소기업")).isEmpty();
        assertThat(ParticipationRecruitmentContext.from("일반 유형 및 DX 유형 신청자격\n국내 공급기업")).isEmpty();
    }

    @Test void originalTextRestartAndExistingRecruitmentMarkersRemainUsable() {
        assertThat(ParticipationRecruitmentContext.from("[PDF 1페이지 시각 판독]\n잘못된 기업 모집\n"
                + "[문서 전체 텍스트 추출]\n" + introduction))
                .contains("총괄주관연구개발기관", "세부주관연구개발기관").doesNotContain("잘못된");
        var data = ParticipationConditionResolver.candidatesWithScopes(
                "운영기관 모집 공고\n□ 신청자격\n중소기업 지원 역량을 가진 비영리법인", List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        assertThat(ParticipationSourceSelection.select(input).outbound(input).sources()).isNotEmpty().allSatisfy(s ->
                assertThat(s.groupLabel().split("\\[공고 대상]", -1)).hasSize(2));
    }
}
