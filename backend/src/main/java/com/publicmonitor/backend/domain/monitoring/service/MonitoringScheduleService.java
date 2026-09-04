package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSchedule;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringScheduleFrequency;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringScheduleRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringScheduleResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.UpdateMonitoringScheduleRequest;
import java.time.DayOfWeek;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.monitoring.schedule.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class MonitoringScheduleService {

    private final MonitoringScheduleRepository scheduleRepository;

    @Transactional(readOnly = true)
    public MonitoringScheduleResponse find() {
        return MonitoringScheduleResponse.from(scheduleRepository.findAll().stream()
                .findFirst()
                .orElseGet(MonitoringSchedule::defaultSchedule));
    }

    @Transactional
    public MonitoringScheduleResponse update(UpdateMonitoringScheduleRequest request) {
        validate(request.frequency(), request.customDays());
        MonitoringSchedule schedule = scheduleRepository.findAll().stream()
                .findFirst()
                .orElseGet(MonitoringSchedule::defaultSchedule);
        schedule.update(request.enabled(), request.frequency(), request.executionTime(), request.customDays());
        return MonitoringScheduleResponse.from(scheduleRepository.save(schedule));
    }

    private void validate(MonitoringScheduleFrequency frequency, Set<DayOfWeek> customDays) {
        if (frequency == MonitoringScheduleFrequency.CUSTOM && customDays.isEmpty()) {
            throw new IllegalArgumentException("직접 선택 일정에는 하나 이상의 요일이 필요합니다.");
        }
    }
}
