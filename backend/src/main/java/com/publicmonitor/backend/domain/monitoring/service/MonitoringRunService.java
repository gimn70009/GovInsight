package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.exception.NoActiveMonitoringSourceException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunSourceRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringSourceRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringRunResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunSummaryResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MonitoringRunService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final MonitoringSourceRepository monitoringSourceRepository;
    private final MonitoringRunRepository monitoringRunRepository;
    private final MonitoringRunSourceRepository monitoringRunSourceRepository;
    private final Clock clock;

    @Transactional
    public CreateMonitoringRunResponse create(MonitoringTriggerType triggerType) {
        List<MonitoringSource> activeSources = monitoringSourceRepository.findAllByEnabledTrueOrderByIdAsc();
        if (activeSources.isEmpty()) {
            throw new NoActiveMonitoringSourceException();
        }

        MonitoringRun run = MonitoringRun.create(
                triggerType,
                activeSources.size(),
                LocalDateTime.now(clock.withZone(SERVICE_ZONE))
        );
        MonitoringRun savedRun = monitoringRunRepository.save(run);

        List<MonitoringRunSource> runSources = activeSources.stream()
                .map(source -> MonitoringRunSource.create(savedRun, source))
                .toList();
        monitoringRunSourceRepository.saveAll(runSources);

        return CreateMonitoringRunResponse.from(savedRun);
    }

    @Transactional(readOnly = true)
    public List<MonitoringRunSummaryResponse> findAll() {
        return monitoringRunRepository.findAllByOrderByRequestedAtDescIdDesc().stream()
                .map(MonitoringRunSummaryResponse::from)
                .toList();
    }
}
