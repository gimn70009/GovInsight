package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ParticipationScopePerformanceTest {
    private String context(String body, String excerpt, boolean frozen) {
        var source = new RequirementSource("NOTICE_BODY", null, null, null, excerpt);
        var rule = new ParticipationRule("EVIDENCE", "원문 조건", "", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(source));
        var index = new ParticipationScopeIndex(frozen);
        index.addDocument(body, List.of(rule));
        return index.context(List.of(source));
    }

    @Test void precedingHeadingDoesNotLookAheadIntoTheCurrentBlock() {
        for (String separator : List.of("\n", "\r\n", "\r", "\u2028")) {
            String body = String.join(separator, "1. 사업개요", "개요 본문", "2", "신청자격", "조건 본문");
            for (boolean frozen : List.of(false, true)) {
                assertThat(context(body, "신청자격" + separator + "조건 본문", frozen)).isEqualTo("사업개요");
                assertThat(context(body, "조건 본문", frozen)).isEqualTo("신청자격");
            }
        }
    }

    @Test void frozenSubsectionsAndArchiveBoundariesRetainTheirContexts() {
        String body = "1. 사업개요\n개요 본문\n3-1.신청자격\n자격 본문\n[파일: 다음 공고.hwpx]\n다음 본문";
        assertThat(context(body, "자격 본문", false)).isEqualTo("신청자격");
        assertThat(context(body, "자격 본문", true)).isEqualTo("사업개요");
        assertThat(context(body, "다음 본문", false)).isEmpty();
        assertThat(context(body, "다음 본문", true)).isEmpty();
    }

    @Test void partialLineEvidenceUsesOnlyItsActualPrefix() {
        assertThat(context("1. 신청자격을 확인합니다.", "을 확인합니다.", false)).isEqualTo("신청자격");
        assertThat(context("1. 사업개요\n2\n신청자격을 확인합니다.", "을 확인합니다.", true)).isEqualTo("신청자격");
    }

    @Test void longMultiSectionNoticePreservesEveryRawBlockWithinTheDetailDeadline() {
        String body = "1. 신청자격\n기관 및 단체가 신청할 수 있습니다.\n"
                + IntStream.range(0, 1000).mapToObj(i -> "□ 참고 항목 " + i + "\n"
                    + "검토 배경과 기술 현황에 대한 설명입니다. ".repeat(4) + "\n")
                    .collect(java.util.stream.Collectors.joining());
        var candidates = assertTimeout(Duration.ofSeconds(10),
                () -> ParticipationConditionResolver.candidatesWithScopes(body, List.of(), null));
        assertThat(candidates.guide().rules()).hasSize(1001);
        assertThat(candidates.guide().rules().getFirst().sources().getFirst().excerpt())
                .contains("기관 및 단체가 신청할 수 있습니다.");
        assertThat(candidates.guide().rules().getLast().sources().getFirst().excerpt()).contains("참고 항목 999");
    }
}
