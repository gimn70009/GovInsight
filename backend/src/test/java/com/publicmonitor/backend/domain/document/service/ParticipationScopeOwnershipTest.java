package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParticipationScopeOwnershipTest {
    @Test void applicantDescriptionsCannotBecomeProgramBadges() {
        assertThat(ParticipationContextLabels.programRole("여러 지역을 연계하여 구성한 협의체")).isTrue();
        assertThat(ParticipationContextLabels.programRole("전문인력을 보유한 제조기업")).isTrue();
        assertThat(ParticipationContextLabels.programRole("공동전환사업")).isFalse();
    }

    @Test void identicalTextInOwnedAndUnownedPositionsIsAmbiguous() {
        String text = "동일한 조건\n동일한 조건";
        var regions = List.of(new ScopeRegion(0, 7, "전환사업"));
        assertThat(ParticipationScopeOwnership.owners(text, regions, "동일한 조건"))
                .containsExactly("전환사업", "적용 범위 확인 필요");
    }

    @Test void organizationAndFieldRemainBoundToTheirProfileAcrossTablesAndExports() {
        String first = "제외 품목 제조기업은 신청 불가합니다.";
        String second = "외부 플랫폼을 사용할 수 있는 기업만 신청 가능합니다.";
        String text = "[파일: 분야별 안내.hwpx]\n1\n가람진흥원\n[산업특화] - [정밀기계]\n"
                + "[표]\n운영사업\t· 일반형, 도약형\n연락처\t123\n[/표]\n"
                + "□ 지원대상\n" + first + "\n2\n나래연합\n[표]\n운영사업\t· 공동전환사업\n[/표]\n"
                + "□ 지원대상\n" + second + "\n[파일: 다른공고.pdf]\n□ 지원대상\n별도 기업 지원\n";
        var regions = ParticipationScopeOwnership.local(ParticipationScopeOwnership.scan(text), text, 0);
        assertThat(ParticipationScopeOwnership.owners(text, regions, first)).containsExactly("산업특화 · 정밀기계 · 가람진흥원");
        assertThat(ParticipationScopeOwnership.owners(text, regions, second)).containsExactly("공동전환사업 · 나래연합");
        assertThat(ParticipationScopeOwnership.owners(text, regions, "별도 기업 지원")).isEmpty();
    }

    @Test void inlineProgramOwnsSubordinateConsentButEndsAtPeerApplicant() {
        String text = "□ 신청자격\n◦ (도입기업) 중소기업\n- “도약형”은 성장 요건 보유기업만 신청 가능\n"
                + "· (확인방법) 자료 전송 동의 필수\n* 미동의 시 탈락\n◦ (공급기업) 등록 기업만 신청 가능\n";
        var regions = ParticipationScopeOwnership.local(ParticipationScopeOwnership.scan(text), text, 0);
        assertThat(ParticipationScopeOwnership.owners(text, regions, "미동의 시 탈락")).containsExactly("도약형");
        assertThat(ParticipationScopeOwnership.owners(text, regions, "(공급기업) 등록 기업만 신청 가능")).isEmpty();
    }

    @Test void outboundAndCachedProjectionUseSameOwnershipEvenWhenModelChoosesBroadTypes() {
        String quote = "◦ 특정 품목을 제조하는 기업은 신청할 수 없습니다.";
        String text = "1\n가람진흥원\n[산업특화] - [정밀기계]\n[표]\n운영사업\t· 일반형, 도약형\n[/표]\n□ 신청자격\n" + quote;
        var data = ParticipationConditionResolver.candidatesWithScopes(text, List.of(), null);
        var input = ParticipationRewriteProjection.input(data.guide(), false, data.scopes());
        var source = ParticipationSourceSelection.select(input).outbound(input).sources().stream()
                .filter(s -> s.excerpt().contains(quote)).findFirst().orElseThrow();
        assertThat(source.scopeRegions()).isNotEmpty();
        var item = new Statement(List.of(source.id()), "SECTOR", quote.substring(2), "", List.of(quote),
                "EXCLUSION", "일반형 / 도약형", null);
        var guide = ParticipationRewriteProjection.guide(data.guide(), input, new Response(List.of(item), List.of(), "READY"), "READY");
        assertThat(guide.rules().getFirst().scope()).isEqualTo("산업특화 · 정밀기계 · 가람진흥원");
    }

    @Test void wireOffsetsCountUnicodeCodePointsAndDoNotBorrowNextProfile() {
        String text = "😀 안내\n- ‘전환사업’은 참여기업만 신청 가능\n· 동의 필요\n□ 다음 구역\n다른 요건";
        var regions = ParticipationScopeOwnership.local(ParticipationScopeOwnership.scan(text), text, 0);
        assertThat(regions.getFirst().start()).isEqualTo(text.codePointCount(0, text.indexOf("- ‘")));
        assertThat(ParticipationScopeOwnership.owners(text, regions, "동의 필요")).containsExactly("전환사업");
        assertThat(ParticipationScopeOwnership.owners(text, regions, "다른 요건")).isEmpty();
        assertThat(ParticipationScopeOwnership.owners(text, regions, "동의 필요\n□ 다음 구역")).contains("적용 범위 확인 필요");
    }

    @Test void flattenedPdfProfileAndFollowingProcedureCannotChangeOwnership() {
        String text = "1\t가람진흥원\t[산업특화]\t- [정밀 기계]\n운영사업\t· 일반형, 도약형\n"
                + "□ 지원대상\n◦ 제조기업만 신청 가능\n5. 전자서명 제출\n설명\n"
                + "2\t나래연합\n운영사업\t· 공동전환사업\n□ 지원대상\n◦ 등록 기업만 신청 가능";
        var regions = ParticipationScopeOwnership.local(ParticipationScopeOwnership.scan(text), text, 0);
        assertThat(ParticipationScopeOwnership.owners(text, regions, "제조기업만 신청 가능"))
                .containsExactly("산업특화 · 정밀 기계 · 가람진흥원");
        assertThat(regions).noneSatisfy(r -> assertThat(r.scope()).contains("전자서명"));
        assertThat(ParticipationScopeOwnership.owners(text, regions, "등록 기업만 신청 가능"))
                .containsExactly("공동전환사업 · 나래연합");
    }

    @Test void concatenatedPdfMetadataRetainsConsortiumOwner() {
        String text = "운영사업· 공동전환사업상생기관명· 가람기업공급기업 컨소· 가람기업지원대상· 제조기업주관기관명· 나래기관\n"
                + "□ 지원대상\n◦ 협력 공급기업만 참여 가능";
        var regions = ParticipationScopeOwnership.local(ParticipationScopeOwnership.scan(text), text, 0);
        assertThat(ParticipationScopeOwnership.owners(text, regions, "협력 공급기업만 참여 가능"))
                .containsExactly("공동전환사업 · 가람기업");
    }
}
