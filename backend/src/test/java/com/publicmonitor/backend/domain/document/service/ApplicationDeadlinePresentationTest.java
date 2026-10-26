package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ApplicationDeadlinePresentationTest {
    static Stream<Arguments> fixedDates() {
        return Stream.of(
                Arguments.of("2026-10-29 (목) 23:59:00, 시스템 접수 기준으로 마감되었습니다.",
                        "2026-10-29 (목) 23:59:00, 시스템 접수 기준 마감입니다."),
                Arguments.of("접수 마감: 2026.10.29.(목) 18:00 마감됐습니다.",
                        "접수 마감: 2026.10.29.(목) 18:00 마감입니다."),
                Arguments.of("2026년 10월 29일 18시 30분까지 종료되었습니다.",
                        "2026년 10월 29일 18시 30분까지입니다."),
                Arguments.of("2025-01-01 마감되었습니다.", "2025-01-01 마감입니다.")
        );
    }

    @ParameterizedTest @MethodSource("fixedDates")
    void preservesCalendarAndReceiptFactsWithoutStoredClosureStatus(String original, String expected) {
        assertThat(ApplicationDeadlinePresentation.normalize(original)).isEqualTo(expected);
        assertThat(ApplicationDeadlinePresentation.normalize(expected)).isEqualTo(expected);
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {
            "", "기한 미확인", "2026-10-29 18:00", "2026-02-30 마감되었습니다.",
            "2026-10-29 25:00 마감되었습니다.", "2026-10-29 예정이었으나 예산 소진으로 조기 마감되었습니다.",
            "2026-10-29 접수가 취소되었습니다.", "모집 정원 충족으로 2026-10-29 전에 마감되었습니다.",
            "2026-10-29까지 접수하되 예산 소진 시 마감됩니다.",
            "국내 2026-10-29 마감되었습니다. 해외 2026-11-02까지 접수합니다.",
            "2026-09-01부터 2026-10-29까지 마감되었습니다.",
            "기존 접수는 2026-10-29 마감되었습니다. 추가 접수는 별도 안내합니다.",
            "행사일 2026-10-29 마감되었습니다.", "2026-10-29 우편 소인분까지 마감되었습니다."
    })
    void leavesExceptionsMultipleDatesAndUnclearFieldsUnchanged(String text) {
        assertThat(ApplicationDeadlinePresentation.normalize(text)).isEqualTo(text);
    }
}
