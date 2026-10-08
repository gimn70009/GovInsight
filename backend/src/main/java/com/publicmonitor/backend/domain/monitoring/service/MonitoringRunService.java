package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClient;
import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClientException;
import com.publicmonitor.backend.domain.monitoring.client.dto.PythonMonitoringJobResponse;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringAlreadyRunningException;
import com.publicmonitor.backend.domain.monitoring.exception.NoActiveMonitoringSourceException;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringJobAcceptanceException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunSourceRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringSourceRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringRunResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunActivityResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunSummaryResponse;
import com.publicmonitor.backend.global.response.PageResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringScheduleRepository;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringSchedulePendingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class MonitoringRunService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final MonitoringSourceRepository monitoringSourceRepository;
    private final MonitoringRunRepository monitoringRunRepository;
    private final MonitoringRunSourceRepository monitoringRunSourceRepository;
    private final PythonMonitoringClient pythonMonitoringClient;
    private final Clock clock;
    private final MonitoringScheduleRepository schedules;
    private final boolean scheduleEnabled;

    public MonitoringRunService(
            MonitoringSourceRepository monitoringSourceRepository,
            MonitoringRunRepository monitoringRunRepository,
            MonitoringRunSourceRepository monitoringRunSourceRepository,
            PythonMonitoringClient pythonMonitoringClient,
            Clock clock,
            MonitoringScheduleRepository schedules,
            @Value("${app.monitoring.schedule.enabled:true}") boolean scheduleEnabled
    ) {
        this.monitoringSourceRepository = monitoringSourceRepository;
        this.monitoringRunRepository = monitoringRunRepository;
        this.monitoringRunSourceRepository = monitoringRunSourceRepository;
        this.pythonMonitoringClient = pythonMonitoringClient;
        this.clock = clock;
        this.schedules = schedules;
        this.scheduleEnabled = scheduleEnabled;
    }


    @Transactional(noRollbackFor = MonitoringJobAcceptanceException.class)
    public CreateMonitoringRunResponse create(MonitoringTriggerType triggerType) {
        // 존재 여부 검사만으로는 동시에 도착한 두 요청을 막지 못하므로 공유 행부터 잠근다.
        List<MonitoringSource> activeSources = monitoringSourceRepository.findAllForRunCreation().stream()
                .filter(MonitoringSource::isEnabled)
                .toList();
        if (triggerType == MonitoringTriggerType.MANUAL && scheduleEnabled) {
            // Match the queue consumer's sources -> schedule lock order.
            var schedule = schedules.findAllForUpdate().stream().findFirst().orElse(null);
            if (schedule != null && schedule.isEnabled() && schedule.getPendingScheduledAt() != null) {
                throw new MonitoringSchedulePendingException();
            }
        }

        if (activeSources.isEmpty()) {
            throw new NoActiveMonitoringSourceException();
        }

        if (monitoringRunRepository.existsByStatusIn(MonitoringRunStatus.inProgress())) {
            throw new MonitoringAlreadyRunningException();
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
    public MonitoringRunActivityResponse activity() {
        return monitoringRunRepository.findFirstByStatusInOrderByRequestedAtAscIdAsc(MonitoringRunStatus.inProgress())
                .map(run -> new MonitoringRunActivityResponse(true, run.getId(), run.getStatus()))
                .orElseGet(() -> new MonitoringRunActivityResponse(false, null, null));
    }

    @Transactional(readOnly = true)
    public PageResponse<MonitoringRunSummaryResponse> findAll(int page, int size) {
        return PageResponse.from(monitoringRunRepository.findSummaries(PageRequest.of(page, size)));
    }
}
