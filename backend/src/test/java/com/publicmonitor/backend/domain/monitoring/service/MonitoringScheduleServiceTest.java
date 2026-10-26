package com.publicmonitor.backend.domain.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSchedule;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringScheduleFrequency;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringScheduleChangedException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringScheduleRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonitoringScheduleServiceTest {

    private static final LocalDateTime RESERVED = LocalDateTime.of(2026, 10, 8, 14, 0);

    @Mock MonitoringScheduleRepository schedules;
    @InjectMocks MonitoringScheduleService service;

    @Test
    void 대기_취소는_일정과_시도_기록을_유지한다() {
        var schedule = pending();
        when(schedules.findAllForUpdate()).thenReturn(List.of(schedule));
        var response = service.cancelPending(RESERVED);
        assertThat(response.pendingScheduledAt()).isNull();
        assertThat(response.enabled()).isTrue();
        assertThat(response.executionTime()).isEqualTo(RESERVED.toLocalTime());
        assertThat(schedule.getLastAttemptedDate()).isEqualTo(RESERVED.toLocalDate());
        assertThat(schedule.dueDate(RESERVED.plusMinutes(1), Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void 오래된_취소_요청이_새로운_대기를_취소하지_않는다() {
        var schedule = pending();
        when(schedules.findAllForUpdate()).thenReturn(List.of(schedule));
        assertThatThrownBy(() -> service.cancelPending(RESERVED.minusDays(1)))
                .isInstanceOf(MonitoringScheduleChangedException.class);
        assertThat(schedule.getPendingScheduledAt()).isEqualTo(RESERVED);
    }

    @Test
    void 대기가_이미_실행되었으면_취소_충돌을_알린다() {
        var schedule = pending();
        schedule.clearPending();
        when(schedules.findAllForUpdate()).thenReturn(List.of(schedule));
        assertThatThrownBy(() -> service.cancelPending(RESERVED))
                .isInstanceOf(MonitoringScheduleChangedException.class);
    }

    private MonitoringSchedule pending() {
        var schedule = MonitoringSchedule.defaultSchedule();
        schedule.update(true, MonitoringScheduleFrequency.DAILY, RESERVED.toLocalTime(), Set.of());
        schedule.queueDue(RESERVED, Duration.ofMinutes(5));
        return schedule;
    }
}
