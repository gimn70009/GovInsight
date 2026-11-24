package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParticipationContextLabelsTest {
    @Test void documentPathsAndFormsAreNotProgramsButActualNamesRemainIntact() {
        for (String label : List.of("사업 개요 - 지원분야", "사업 개요 - 수행내용", "사업개요>지원분야",
                "사업개요:지원분야", "사업개요/지원분야", "사업개요·지원분야", "자격 확인서",
                "신청 자격 적정성 확인서", "연구기관 모집 공고"))
            assertThat(ParticipationContextLabels.structural(label)).as(label).isTrue();
        for (String label : List.of("연구기관 역량강화 지원사업", "소재·부품·장비", "일반 유형",
                "사업 개요 작성 지원사업", "A-B형", "공급기업 역량진단", "주관기관"))
            assertThat(ParticipationContextLabels.structural(label)).as(label).isFalse();
    }

    @Test void applicantLabelsCannotBorrowAnotherRowOrAddAnUnsupportedRole() {
        String quote = "연구기관은 관련 분야의 연구 실적을 보유해야 합니다.";
        assertThat(ParticipationContextLabels.audience("기업지원기관", quote)).isNull();
        assertThat(ParticipationContextLabels.audience("연구기관(주관기관)", quote)).isEqualTo("연구기관");
        assertThat(ParticipationContextLabels.audience("사업 개요 - 지원분야", quote)).isNull();
        assertThat(ParticipationContextLabels.audience("총괄주관연구개발기관", "반도체와 바이오 분야를 지원합니다.")).isNull();
    }

    @Test void legitimateExclusionSubjectsAndListsSurviveWithoutCallingPeopleInstitutions() {
        assertThat(ParticipationContextLabels.audience("신청기관(기관 및 개인)",
                "참여제한 중인 자(기관 및 개인)는 신청 불가")).isEqualTo("기관 및 개인");
        assertThat(ParticipationContextLabels.audience("기업 신청자", "기업의 경우 부도상태인가?")).isEqualTo("기업");
        assertThat(ParticipationContextLabels.audience("연구책임자 및 참여연구자",
                "연구책임자로 최대 3개, 참여연구자로 최대 5개를 초과하였는가?"))
                .isEqualTo("연구책임자 및 참여연구자");
        assertThat(ParticipationContextLabels.audience("주관·공동연구개발기관 및 연구책임자",
                "주관연구개발기관, 공동연구개발기관, 연구책임자가 의무를 불이행한 경우"))
                .isEqualTo("주관·공동연구개발기관 및 연구책임자");
        assertThat(ParticipationContextLabels.audience("협·단체·연구기관·기업지원기관",
                "협·단체, 연구기관(국공립·민간), 기업지원기관이 신청할 수 있습니다."))
                .isEqualTo("협·단체·연구기관·기업지원기관");
    }
}
