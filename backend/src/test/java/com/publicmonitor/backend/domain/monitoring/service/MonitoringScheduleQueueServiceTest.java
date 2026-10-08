package com.publicmonitor.backend.domain.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSchedule;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringScheduleFrequency;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringJobAcceptanceException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringScheduleRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringSourceRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringRunResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonitoringScheduleQueueServiceTest {

    @Mock MonitoringScheduleRepository schedules;
    @Mock MonitoringSourceRepository sources;
    @Mock MonitoringRunRepository runs;
    @Mock MonitoringRunService runService;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-07T00:01:00Z"), ZoneOffset.UTC);
    private final Duration window = Duration.ofMinutes(5);
    private final LocalDateTime scheduledAt = LocalDateTime.of(2026, 9, 7, 9, 0);
    private MonitoringScheduleQueueService service;
    private MonitoringSchedule schedule;

    @BeforeEach
    void setup() {
        service = new MonitoringScheduleQueueService(schedules, sources, runs, runService, clock, window);
        schedule = MonitoringSchedule.defaultSchedule();
        schedule.update(true, MonitoringScheduleFrequency.DAILY, LocalTime.of(9, 0), Set.of());
    }

    private void pending() {
        schedule.queueDue(scheduledAt, window);
        when(schedules.findAllForUpdate()).thenReturn(List.of(schedule));
    }

    private void activeSource() {
        when(sources.findAllForRunCreation()).thenReturn(List.of(MonitoringSource.create(
                "기관", "게시판", null, "https://example.com", null, 1, true)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 86400, 86401})
    void 잘못된_예약_유예시간은_시작_시_거절한다(long seconds) {
        assertThatThrownBy(() -> new MonitoringScheduleQueueService(
                schedules, sources, runs, runService, clock, Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 예정_회차를_잠금_후_등록한다() {
        when(schedules.findAll()).thenReturn(List.of(schedule));
        when(schedules.findAllForUpdate()).thenReturn(List.of(schedule));
        assertThat(service.hasWork()).isTrue();

        service.enqueueDue();
        service.enqueueDue();

        assertThat(schedule.getPendingScheduledAt()).isEqualTo(scheduledAt);
        assertThat(schedule.getLastAttemptedDate()).isEqualTo(scheduledAt.toLocalDate());
        verifyNoInteractions(sources, runs, runService);
    }

    @Test
    void 유예시간_이후에도_저장된_대기는_작업으로_조회된다() {
        schedule.queueDue(scheduledAt, window);
        when(schedules.findAll()).thenReturn(List.of(schedule));
        var later = new MonitoringScheduleQueueService(schedules, sources, runs, runService,
                Clock.offset(clock, Duration.ofHours(12)), window);

        assertThat(later.hasWork()).isTrue();
        schedule.update(false, MonitoringScheduleFrequency.DAILY, LocalTime.of(9, 0), Set.of());
        assertThat(later.hasWork()).isFalse();
    }

    @Test
    void 비활성으로_바뀐_일정은_대기를_새로_등록하지_않는다() {
        schedule.update(false, MonitoringScheduleFrequency.DAILY, LocalTime.of(9, 0), Set.of());
        when(schedules.findAllForUpdate()).thenReturn(List.of(schedule));
        service.enqueueDue();
        assertThat(schedule.getPendingScheduledAt()).isNull();
    }

    @Test
    void 실행이_진행_중이면_저장된_대기를_보존한다() {
        pending();
        activeSource();
        when(runs.existsByStatusIn(any())).thenReturn(true);

        assertThat(service.startPending()).isEmpty();
        assertThat(schedule.getPendingScheduledAt()).isEqualTo(scheduledAt);
        verifyNoInteractions(runService);
    }

    @Test
    void 활성_소스가_없으면_저장된_대기를_보존한다() {
        pending();
        when(sources.findAllForRunCreation()).thenReturn(List.of());

        assertThat(service.startPending()).isEmpty();
        assertThat(schedule.getPendingScheduledAt()).isEqualTo(scheduledAt);
        verifyNoInteractions(runs, runService);
    }

    @Test
    void 취소된_대기는_실행하지_않는다() {
        pending();
        schedule.cancelPending(scheduledAt);

        assertThat(service.startPending()).isEmpty();
        verifyNoInteractions(runs, runService);
    }

    @Test
    void 소스_다음_일정을_잠근_뒤_대기_한_건을_실행한다() {
        pending();
        activeSource();
        var response = mock(CreateMonitoringRunResponse.class);
        when(runService.create(MonitoringTriggerType.SCHEDULED)).thenAnswer(invocation -> {
            assertThat(schedule.getPendingScheduledAt()).isNull();
            return response;
        });

        assertThat(service.startPending()).contains(response);
        assertThat(service.startPending()).isEmpty();
        var order = inOrder(sources, schedules, runs, runService);
        order.verify(sources).findAllForRunCreation();
        order.verify(schedules).findAllForUpdate();
        order.verify(runs).existsByStatusIn(any());
        order.verify(runService).create(MonitoringTriggerType.SCHEDULED);
        verify(runService, times(1)).create(MonitoringTriggerType.SCHEDULED);
    }

    @Test
    void 기록된_접수_실패는_다음_조회에서_중복_시도하지_않는다() {
        pending();
        activeSource();
        when(runService.create(MonitoringTriggerType.SCHEDULED))
                .thenThrow(new MonitoringJobAcceptanceException());

        assertThatThrownBy(service::startPending).isInstanceOf(MonitoringJobAcceptanceException.class);
        assertThat(schedule.getPendingScheduledAt()).isNull();
        assertThat(service.startPending()).isEmpty();
        verify(runService, times(1)).create(MonitoringTriggerType.SCHEDULED);
    }
}
