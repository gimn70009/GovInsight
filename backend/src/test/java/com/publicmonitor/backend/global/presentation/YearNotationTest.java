package com.publicmonitor.backend.global.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class YearNotationTest {
    @ParameterizedTest
    @MethodSource("noticeDates")
    void expandsNoticeDatesWithoutChangingTheirMeaning(String original, String expected) {
        assertThat(YearNotation.display(original)).isEqualTo(expected);
        assertThat(YearNotation.display(expected)).isEqualTo(expected);
    }

    static Stream<Arguments> noticeDates() {
        return Stream.of(
                Arguments.of("3개 회계연도 말(’23~’25) 결산 재무제표상", "3개 회계연도 말(2023~2025) 결산 재무제표상"),
                Arguments.of("특구 지정신청(’27.3월) 및 심의(’27.4월)",
                        "특구 지정신청(2027년 3월) 및 심의(2027년 4월)"),
                Arguments.of("'26년부터 ‘27년까지", "2026년부터 2027년까지"),
                Arguments.of("접수 ’26.11.6 16:00까지", "접수 2026년 11월 6일 16:00까지"),
                Arguments.of("’27/03/05일까지 제출", "2027년 3월 5일까지 제출"),
                Arguments.of("’24.2.29.(목)", "2024년 2월 29일(목)"),
                Arguments.of("기간 ’23∼’25년", "기간 2023∼2025년"),
                Arguments.of("1923년 자료의 ’23년 및 ’23.3월", "1923년 자료의 1923년 및 1923년 3월"),
                Arguments.of("2099년 이후 ’00년", "2099년 이후 2100년"),
                Arguments.of("권역별 설명회(’26.10.13~10.14)에서 작성",
                        "권역별 설명회(2026년 10월 13일~10월 14일)에서 작성"),
                Arguments.of("기간 '26.10.13 ~ 10.14", "기간 2026년 10월 13일~10월 14일"),
                Arguments.of("기간 ‘26. 10. 13.∼10. 14.", "기간 2026년 10월 13일∼10월 14일"),
                Arguments.of("’26/10/13–10/14", "2026년 10월 13일–10월 14일"),
                Arguments.of("’26.10.13~14일까지", "2026년 10월 13일~10월 14일까지"),
                Arguments.of("’26.10.13~’26.10.14", "2026년 10월 13일~10월 14일"),
                Arguments.of("’26.10.13~2026.10.14", "2026년 10월 13일~10월 14일"),
                Arguments.of("’26.12.30~’27.1.2", "2026년 12월 30일~2027년 1월 2일"),
                Arguments.of("’24.2.28~2.29", "2024년 2월 28일~2월 29일"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "‘규제자유특구’와 '제출 서류', don't, '23', '27년'",
            "’99년 연혁 / 2027.3월 / ‘2027년’",
            "잘못된 날짜 ’27.13월, ’27.2.29, ’27.3.55 및 ’27.0월",
            "역순 ’25~’23년",
            "잘못된 기간 ’26.10.13~10.32, ’26.2.28~2.29, ’26.13.1~13.2",
            "역순 날짜 ’26.10.14~10.13, ’26.12.30~1.2",
            "명시 연도의 월 누락 ’26.10.13~’27.14",
            "'26.10.13~10.14'",
            "[’26.10.13~10.14 서식](https://example.org/'26.10.13~10.14.pdf)",
            "불명확한 범위 ’99~’24년",
            "파일 ’27년_사업계획.pdf, ’27년.pdf 및 ’27.3월.hwp",
            "[’27년 신청서](https://example.org/'27.3월?a=1&b=2)",
            "https://example.org/'27.3월 www.example.org/'27년",
            "코드 `’27.3월` / user'27@example.org"
    })
    void preservesQuotesAmbiguousDatesAndSourceIdentifiers(String original) {
        assertThat(YearNotation.display(original)).isEqualTo(original);
    }

    @Test
    void expandsOnlyProseAroundProtectedLinks() {
        assertThat(YearNotation.display("’27.3월 제출 [’27년 서식](https://example.org/'27년.pdf) ’27.4월 심의"))
                .isEqualTo("2027년 3월 제출 [’27년 서식](https://example.org/'27년.pdf) 2027년 4월 심의");
        assertThat(YearNotation.display(null)).isNull();
        assertThat(YearNotation.display("")).isEmpty();
    }
}
