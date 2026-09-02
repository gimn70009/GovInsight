package com.publicmonitor.backend.domain.monitoring.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonitoringScheduleTest {

    @Test
    void 평일_일정은_월요일에만_정해진_시각에_실행한다() {
        MonitoringSchedule schedule = MonitoringSchedule.defaultSchedule();
        schedule.update(true, MonitoringScheduleFrequency.WEEKDAYS, LocalTime.of(9, 30), Set.of());

        assertThat(schedule.isDue(LocalDate.of(2026, 9, 7), LocalTime.of(9, 30))).isTrue();
        assertThat(schedule.isDue(LocalDate.of(2026, 9, 6), LocalTime.of(9, 30))).isFalse();
        assertThat(schedule.isDue(LocalDate.of(2026, 9, 7), LocalTime.of(9, 31))).isFalse();
    }

    @Test
    void 직접_선택한_요일만_실행하고_같은_날에는_다시_실행하지_않는다() {
        MonitoringSchedule schedule = MonitoringSchedule.defaultSchedule();
        LocalDate wednesday = LocalDate.of(2026, 9, 2);
        schedule.update(
                true,
                MonitoringScheduleFrequency.CUSTOM,
                LocalTime.of(14, 0),
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        );

        assertThat(schedule.isDue(wednesday, LocalTime.of(14, 0))).isTrue();
        schedule.markAttempted(wednesday);
        assertThat(schedule.isDue(wednesday, LocalTime.of(14, 0))).isFalse();
    }

    @Test
    void 실행_시각을_바꾸면_같은_날에도_새_일정을_실행할_수_있다() {
        MonitoringSchedule schedule = MonitoringSchedule.defaultSchedule();
        LocalDate today = LocalDate.of(2026, 9, 2);
        schedule.update(true, MonitoringScheduleFrequency.DAILY, LocalTime.of(9, 0), Set.of());
        schedule.markAttempted(today);

        schedule.update(true, MonitoringScheduleFrequency.DAILY, LocalTime.of(10, 30), Set.of());

        assertThat(schedule.isDue(today, LocalTime.of(10, 30))).isTrue();
    }
}
