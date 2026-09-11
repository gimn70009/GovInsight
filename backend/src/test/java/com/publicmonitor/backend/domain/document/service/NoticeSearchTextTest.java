package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NoticeSearchTextTest {
    @Test
    void searchTextContractMatchesPythonForMissingValuesAndRealRestrictions() throws IOException {
        try (var input = getClass().getResourceAsStream("/similarity/text-cases.json")) {
            for (var row : new ObjectMapper().readTree(input.readAllBytes())) {
                String value = row.path("input").isNull() ? null : row.path("input").asText();
                assertThat(NoticeSearchText.clean(value)).as(value).isEqualTo(row.path("expected").asText());
            }
        }
    }

    @Test
    void detectsLegacyEmbeddingContaminationWithoutRejectingRealNegativeConditions() {
        assertThat(NoticeSearchText.hasUnknown("핵심 주제: 반도체\n사업 목적: 결함탐지.\n지원 대상: 원문에서 확인하지 못했습니다.")).isTrue();
        assertThat(NoticeSearchText.hasUnknown("지원 대상: 비영리기관은 신청할 수 없습니다.")).isFalse();
        assertThat(NoticeSearchText.summaryPurpose("반도체 검사 사업입니다. 공정 결함탐지를 지원합니다. 접수는 9월입니다."))
                .isEqualTo("반도체 검사 사업입니다. 공정 결함탐지를 지원합니다.");
    }
}
