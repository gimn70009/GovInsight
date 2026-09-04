package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClient;
import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClientException;
import com.publicmonitor.backend.domain.monitoring.client.dto.PythonMonitoringJobResponse;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.exception.NoActiveMonitoringSourceException;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringJobAcceptanceException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunSourceRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringSourceRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringRunResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunSummaryResponse;
import com.publicmonitor.backend.global.response.PageResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class MonitoringRunService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final MonitoringSourceRepository monitoringSourceRepository;
    private final MonitoringRunRepository monitoringRunRepository;
    private final MonitoringRunSourceRepository monitoringRunSourceRepository;
    private final PythonMonitoringClient pythonMonitoringClient;
    private final Clock clock;

    @Transactional(noRollbackFor = MonitoringJobAcceptanceException.class)
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

        LocalDateTime now = LocalDateTime.now(clock.withZone(SERVICE_ZONE));
        try {
            PythonMonitoringJobResponse response = pythonMonitoringClient.accept(savedRun.getId(), activeSources);
            savedRun.accept(response.jobId().toString(), now);
        } catch (PythonMonitoringClientException exception) {
            log.error("Python 모니터링 작업 접수에 실패했습니다. runId={}", savedRun.getId(), exception);
            savedRun.failAcceptance("Python 모니터링 작업 접수에 실패했습니다.", now);
            throw new MonitoringJobAcceptanceException();
        }

        return CreateMonitoringRunResponse.from(savedRun);
    }

    @Transactional(readOnly = true)
    public PageResponse<MonitoringRunSummaryResponse> findAll(int page, int size) {
        return PageResponse.from(monitoringRunRepository.findSummaries(PageRequest.of(page, size)));
    }
}
