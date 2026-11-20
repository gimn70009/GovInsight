package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubmissionTitleConditionTest {
    @Test void explainsEachExplicitTargetWithoutConditioningTheNeighbour() {
        assertThat(SubmissionTitleCondition.sentences("등기부등본(법인해당시), 사업자등록증"))
                .containsExactly("등기부등본은 법인에 해당하는 경우 제출해야 합니다.");
        assertThat(SubmissionTitleCondition.sentences("위임장(대리인 해당 시), 확인서(공동기관에 해당 시)"))
                .containsExactly("위임장은 대리인에 해당하는 경우 제출해야 합니다.",
                        "확인서는 공동기관에 해당하는 경우 제출해야 합니다.");
    }

    @Test void doesNotInventTargetsOrChangeAlternativesAndExceptions() {
        for (String title : List.of("확인서(해당 시)", "신청서 또는 확인서(법인 해당 시)",
                "확인서(법인 해당 시, 개인 제외)", "확인서(법인(지점) 해당 시)",
                "확인서(법인 제외 해당 시)", "신청서 및 확인서(법인 해당 시)"))
            assertThat(SubmissionTitleCondition.sentences(title)).as(title).isEmpty();
    }

    @Test void changesOnlyTheTitleEchoAndKeepsSourceAndUnrelatedConditions() {
        String title = "등기부등본(법인해당시), 사업자등록증";
        var source = new RequirementSource("NOTICE_BODY", null, "제출서류", null, "5\t" + title + "\t각 1부");
        var doc = new SubmissionDocument(title, "각 1부", "CONDITIONAL", "APPLICATION", null, source,
                List.of(title), List.of(title + ".", "최근 3개월 이내 발급한 서류를 제출합니다."), List.of("PDF"));
        var result = SubmissionTitleCondition.explain(doc);
        assertThat(result).usingRecursiveComparison().ignoringFields("conditions").isEqualTo(doc);
        assertThat(result.conditions()).containsExactly("등기부등본은 법인에 해당하는 경우 제출해야 합니다.",
                "최근 3개월 이내 발급한 서류를 제출합니다.");
    }
}
