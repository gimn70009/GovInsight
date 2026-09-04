package com.publicmonitor.backend.domain.monitoring.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSchedule;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringScheduleFrequency;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringScheduleRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringRunResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonitoringScheduleRunnerTest {

    @Mock MonitoringScheduleRepository scheduleRepository;
    @Mock MonitoringRunRepository runRepository;
    @Mock MonitoringRunService runService;

    private MonitoringScheduleRunner runner;
    private MonitoringSchedule schedule;

    @BeforeEach
    void setUp() {
        schedule = MonitoringSchedule.defaultSchedule();
        schedule.update(true, MonitoringScheduleFrequency.DAILY, LocalTime.of(9, 0), Set.of());
        runner = new MonitoringScheduleRunner(
                scheduleRepository,
                runRepository,
                runService,
                Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC)
        );
        given(scheduleRepository.findAll()).willReturn(List.of(schedule));
    }

    @Test
    void 예약_시각이면_자동_실행을_시작하고_시도일을_기록한다() {
        given(runRepository.existsByStatusIn(org.mockito.ArgumentMatchers.<Collection<MonitoringRunStatus>>any()))
                .willReturn(false);
        given(runService.create(MonitoringTriggerType.SCHEDULED)).willReturn(org.mockito.Mockito.mock(CreateMonitoringRunResponse.class));

        runner.runIfDue();

        verify(scheduleRepository).save(schedule);
        verify(runService).create(MonitoringTriggerType.SCHEDULED);
    }

    @Test
    void 진행_중인_실행이_있으면_해당_회차를_건너뛴다() {
        given(runRepository.existsByStatusIn(org.mockito.ArgumentMatchers.<Collection<MonitoringRunStatus>>any()))
                .willReturn(true);

        runner.runIfDue();

        verify(scheduleRepository).save(schedule);
        verify(runService, never()).create(MonitoringTriggerType.SCHEDULED);
    }
}
