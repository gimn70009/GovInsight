package com.publicmonitor.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailPreparationScheduleTest {
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 11, 3, 9, 0);

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "전산등록 마감 2026-11-27 12:00, 서류접수(우편 도착 기준) 마감 2026-11-27 16:00.|17일 내 준비 착수 권장|마감까지 D-24일",
            "회사 행동 기한은 2026-11-20 16:00까지 온라인 제출이며 남은 17일입니다.|10일 내 준비 착수 권장|마감까지 D-17일",
            "온라인 2026-11-10, 우편 2026-11-12|오늘 준비 착수 권장|마감까지 D-7일",
            "2026-11-13|3일 내 준비 착수 권장|마감까지 D-10일",
            "2026.11.10. 18:00 (온라인 SMPP 제출 마감)|오늘 준비 착수 권장|마감까지 D-7일",
            "2026년 11월 8일 18시|최소 준비 기간보다 2일 부족|마감까지 D-5일",
            "2026-11-03|오늘 마감 · 제출 가능 여부 확인|오늘 마감 D-day",
            "2026-11-02|접수가 마감되었습니다.|마감 지남",
            "2026-11-01 ~ 2026-11-10|오늘 준비 착수 권장|마감까지 D-7일",
            "2026.11.1.(일) 09:00 ~ 2026.11.10.(화) 18:00|오늘 준비 착수 권장|마감까지 D-7일"
    })
    void 발송일과_준비기간으로_일정을_계산한다(String deadline, String message, String badge) {
        assertThat(EmailPreparationSchedule.from(deadline, SENT_AT, 7))
                .isEqualTo(new EmailPreparationSchedule(message, badge));
    }

    @ParameterizedTest
    @ValueSource(strings = {"확인 필요", "상시 접수 (예산 소진 시 종료)", "2026-11-10 또는 예산 소진 시",
            "2026-11-10, 우편 11월 12일",
            "2026.11.10 / 우편 11.12", "2026-11-01 ~ 11-10", "2026-11-01부터 10일까지",
            "11월 10일", "2026-02-30", "2026-11-10 25:00", "2026-11-10에서 2026-11-17로 연장",
            "2026-11-10 ~ 2026-11-01", "2026-11-03 10:00 또는 18:00", "2026-11-03 이후"})
    void 불명확한_기한과_복수_제출기한을_임의로_합치지_않는다(String deadline) {
        assertThat(EmailPreparationSchedule.from(deadline, SENT_AT, 7)).isEqualTo(EmailPreparationSchedule.unknown());
    }

    @Test
    void 마감_당일의_확정_시각과_오전_오후를_반영한다() {
        assertThat(EmailPreparationSchedule.from("2026-11-03 09:00", SENT_AT, 7).deadlineBadge()).isEqualTo("마감 지남");
        assertThat(EmailPreparationSchedule.from("2026년 11월 3일 오전 9시", SENT_AT, 7).deadlineBadge()).isEqualTo("마감 지남");
        assertThat(EmailPreparationSchedule.from("2026년 11월 3일 오후 6시", SENT_AT, 7).deadlineBadge()).isEqualTo("오늘 마감 D-day");
    }

    @Test
    void 복수_필수_접수는_빠른_시각을_반영하고_이미_지난_접수를_숨기지_않는다() {
        String deadline = "전산등록 마감 2026-11-27 12:00, 우편접수 마감 2026-11-27 16:00";
        assertThat(EmailPreparationSchedule.from(deadline, LocalDateTime.of(2026, 11, 27, 13, 0), 7)
                .deadlineBadge()).isEqualTo("마감 지남");
        assertThat(EmailPreparationSchedule.from("온라인 2026-11-01, 우편 2026-11-10", SENT_AT, 7)
                .deadlineBadge()).isEqualTo("마감 지남");
        assertThat(EmailPreparationSchedule.from("회사 행동 기한은 2026-11-20 16:00까지 온라인 제출이며 남은 17일입니다.",
                SENT_AT.plusDays(5), 7).deadlineBadge()).isEqualTo("마감까지 D-12일");
    }

    @Test
    void 준비기간이_없어도_남은_날짜는_표시하되_착수를_추정하지_않는다() {
        assertThat(EmailPreparationSchedule.from("2026-11-10", SENT_AT, null))
                .isEqualTo(new EmailPreparationSchedule("준비 기간을 설정해 주세요.", "마감까지 D-7일"));
        assertThat(EmailPreparationSchedule.from(null, SENT_AT, 7)).isEqualTo(EmailPreparationSchedule.unknown());
    }

    @Test
    void 재전송하면_그날_기준으로_계산한다() {
        assertThat(EmailPreparationSchedule.from("2026-11-10", SENT_AT.plusDays(2), 7))
                .isEqualTo(new EmailPreparationSchedule("최소 준비 기간보다 2일 부족", "마감까지 D-5일"));
    }
}
