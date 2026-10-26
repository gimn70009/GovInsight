package com.publicmonitor.backend.domain.monitoring.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonitoringScheduleTest {

    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);

    private MonitoringSchedule daily(LocalTime time) {
        var schedule = MonitoringSchedule.defaultSchedule();
        schedule.update(true, MonitoringScheduleFrequency.DAILY, time, Set.of());
        return schedule;
    }

    @Test
    void 평일_일정은_선택한_요일의_예약부터_유예시간까지_실행한다() {
        var schedule = MonitoringSchedule.defaultSchedule();
        schedule.update(true, MonitoringScheduleFrequency.WEEKDAYS, LocalTime.of(9, 30), Set.of());

        assertThat(schedule.dueDate(MONDAY.atTime(9, 30), WINDOW)).contains(MONDAY);
        assertThat(schedule.dueDate(MONDAY.atTime(9, 31), WINDOW)).contains(MONDAY);
        assertThat(schedule.dueDate(MONDAY.minusDays(1).atTime(9, 30), WINDOW)).isEmpty();
    }

    @Test
    void 유예시간_경계는_포함하고_직전과_초과는_실행하지_않는다() {
        var schedule = daily(LocalTime.of(9, 0));

        assertThat(schedule.dueDate(MONDAY.atTime(8, 59, 59), WINDOW)).isEmpty();
        assertThat(schedule.dueDate(MONDAY.atTime(9, 0), WINDOW)).contains(MONDAY);
        assertThat(schedule.dueDate(MONDAY.atTime(9, 5), WINDOW)).contains(MONDAY);
        assertThat(schedule.dueDate(MONDAY.atTime(9, 5).plusNanos(1), WINDOW)).isEmpty();
        assertThat(schedule.dueDate(MONDAY.atTime(12, 0), WINDOW)).isEmpty();
    }

    @Test
    void 직접_선택한_요일만_실행하고_같은_예약은_다시_실행하지_않는다() {
        var schedule = MonitoringSchedule.defaultSchedule();
        var wednesday = LocalDate.of(2026, 9, 2);
        schedule.update(true, MonitoringScheduleFrequency.CUSTOM, LocalTime.of(14, 0),
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY));

        assertThat(schedule.dueDate(wednesday.atTime(14, 2), WINDOW)).contains(wednesday);
        schedule.markAttempted(wednesday);
        assertThat(schedule.dueDate(wednesday.atTime(14, 3), WINDOW)).isEmpty();
        assertThat(schedule.dueDate(wednesday.plusDays(1).atTime(14, 0), WINDOW)).isEmpty();
    }

    @Test
    void 자정을_넘긴_예약은_예약일의_요일과_시도일로_판단한다() {
        var schedule = MonitoringSchedule.defaultSchedule();
        schedule.update(true, MonitoringScheduleFrequency.CUSTOM, LocalTime.of(23, 59),
                Set.of(DayOfWeek.MONDAY));
        var delayed = MONDAY.plusDays(1).atTime(0, 2);

        assertThat(schedule.dueDate(delayed, WINDOW)).contains(MONDAY);
        assertThat(schedule.dueDate(MONDAY.plusDays(1).atTime(0, 4), WINDOW)).contains(MONDAY);
        assertThat(schedule.dueDate(MONDAY.plusDays(1).atTime(0, 4, 1), WINDOW)).isEmpty();
        schedule.markAttempted(MONDAY);
        assertThat(schedule.dueDate(delayed, WINDOW)).isEmpty();
    }

    @Test
    void 실행_시각을_바꾸면_같은_날에도_새_일정을_실행할_수_있다() {
        var schedule = daily(LocalTime.of(9, 0));
        schedule.markAttempted(MONDAY);

        schedule.update(true, MonitoringScheduleFrequency.DAILY, LocalTime.of(10, 30), Set.of());

        assertThat(schedule.dueDate(MONDAY.atTime(9, 2), WINDOW)).isEmpty();
        assertThat(schedule.dueDate(MONDAY.atTime(10, 31), WINDOW)).contains(MONDAY);
    }

    @Test
    void 같은_설정을_저장해도_이미_소비한_예약은_복구하지_않는다() {
        var schedule = daily(LocalTime.of(9, 0));
        schedule.markAttempted(MONDAY);
        schedule.update(true, MonitoringScheduleFrequency.DAILY, LocalTime.of(9, 0), Set.of());

        assertThat(schedule.dueDate(MONDAY.atTime(9, 1), WINDOW)).isEmpty();
        assertThat(schedule.dueDate(MONDAY.plusDays(1).atTime(9, 1), WINDOW)).contains(MONDAY.plusDays(1));
    }

    @Test
    void 비활성_일정은_유예시간에도_실행하지_않는다() {
        var schedule = daily(LocalTime.of(9, 0));
        schedule.update(false, MonitoringScheduleFrequency.DAILY, LocalTime.of(9, 0), Set.of());

        assertThat(schedule.dueDate(MONDAY.atTime(9, 1), WINDOW)).isEmpty();
    }

    @Test
    void 설정한_유예시간을_적용한다() {
        var schedule = daily(LocalTime.of(9, 0));
        var now = LocalDateTime.of(MONDAY, LocalTime.of(9, 8));

        assertThat(schedule.dueDate(now, WINDOW)).isEmpty();
        assertThat(schedule.dueDate(now, Duration.ofMinutes(10))).contains(MONDAY);
    }

    @Test
    void 겹친_회차는_최초_예약_한_건으로_합치고_각_회차를_소비한다() {
        var schedule = daily(LocalTime.of(9, 0));
        assertThat(schedule.queueDue(MONDAY.atTime(9, 1), WINDOW)).isTrue();
        assertThat(schedule.queueDue(MONDAY.atTime(9, 2), WINDOW)).isFalse();
        assertThat(schedule.getPendingScheduledAt()).isEqualTo(MONDAY.atTime(9, 0));

        assertThat(schedule.queueDue(MONDAY.plusDays(1).atTime(9, 1), WINDOW)).isTrue();
        assertThat(schedule.getPendingScheduledAt()).isEqualTo(MONDAY.atTime(9, 0));
        assertThat(schedule.getLastAttemptedDate()).isEqualTo(MONDAY.plusDays(1));
        schedule.clearPending();
        assertThat(schedule.queueDue(MONDAY.plusDays(1).atTime(9, 2), WINDOW)).isFalse();
        assertThat(schedule.getPendingScheduledAt()).isNull();
    }

    @Test
    void 유예시간이_지나도_등록된_대기는_삭제하지_않는다() {
        var schedule = daily(LocalTime.of(9, 0));
        schedule.queueDue(MONDAY.atTime(9, 0), WINDOW);

        assertThat(schedule.queueDue(MONDAY.atTime(12, 0), WINDOW)).isFalse();
        assertThat(schedule.getPendingScheduledAt()).isEqualTo(MONDAY.atTime(9, 0));
    }

    @Test
    void 보고_있던_대기만_취소하고_같은_회차를_다시_등록하지_않는다() {
        var schedule = daily(LocalTime.of(9, 0));
        schedule.queueDue(MONDAY.atTime(9, 0), WINDOW);

        assertThat(schedule.cancelPending(null)).isFalse();
        assertThat(schedule.cancelPending(MONDAY.minusDays(1).atTime(9, 0))).isFalse();
        assertThat(schedule.getPendingScheduledAt()).isEqualTo(MONDAY.atTime(9, 0));
        assertThat(schedule.cancelPending(MONDAY.atTime(9, 0))).isTrue();
        assertThat(schedule.cancelPending(MONDAY.atTime(9, 0))).isFalse();
        assertThat(schedule.queueDue(MONDAY.atTime(9, 1), WINDOW)).isFalse();
    }

    @Test
    void 동일한_설정은_대기를_보존하고_실제_변경은_기존_대기를_취소한다() {
        var schedule = daily(LocalTime.of(9, 0));
        schedule.queueDue(MONDAY.atTime(9, 0), WINDOW);
        schedule.update(true, MonitoringScheduleFrequency.DAILY, LocalTime.of(9, 0), Set.of());
        assertThat(schedule.getPendingScheduledAt()).isEqualTo(MONDAY.atTime(9, 0));

        schedule.update(true, MonitoringScheduleFrequency.DAILY, LocalTime.of(10, 0), Set.of());
        assertThat(schedule.getPendingScheduledAt()).isNull();
        assertThat(schedule.getLastAttemptedDate()).isNull();
        assertThat(schedule.queueDue(MONDAY.atTime(10, 0), WINDOW)).isTrue();
        schedule.update(false, MonitoringScheduleFrequency.DAILY, LocalTime.of(10, 0), Set.of());
        assertThat(schedule.getPendingScheduledAt()).isNull();
    }

}
