package com.publicmonitor.backend.domain.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClient;
import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringClientException;
import com.publicmonitor.backend.domain.monitoring.client.PythonMonitoringJobStatus;
import com.publicmonitor.backend.domain.monitoring.client.dto.PythonMonitoringJobResponse;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSourceStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringProcessingMode;
import com.publicmonitor.backend.domain.monitoring.exception.NoActiveMonitoringSourceException;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringJobAcceptanceException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunSourceRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringSourceRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringRunResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunSummaryResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MonitoringRunServiceTest {

    @Mock
    private MonitoringSourceRepository monitoringSourceRepository;

    @Mock
    private MonitoringRunRepository monitoringRunRepository;

    @Mock
    private MonitoringRunSourceRepository monitoringRunSourceRepository;

    @Mock
    private PythonMonitoringClient pythonMonitoringClient;

    private MonitoringRunService monitoringRunService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T01:00:00Z"), ZoneOffset.UTC);
        monitoringRunService = new MonitoringRunService(
                monitoringSourceRepository,
                monitoringRunRepository,
                monitoringRunSourceRepository,
                pythonMonitoringClient,
                clock
        );
    }

    @Test
    void 활성_소스를_대상으로_수동_실행을_생성한다() {
        List<MonitoringSource> sources = List.of(createSource("서울시"), createSource("환경부"));
        given(monitoringSourceRepository.findAllByEnabledTrueOrderByIdAsc()).willReturn(sources);
        given(monitoringRunRepository.save(any(MonitoringRun.class))).willAnswer(invocation -> {
            MonitoringRun run = invocation.getArgument(0);
            ReflectionTestUtils.setField(run, "id", 1L);
            return run;
        });
        UUID jobId = UUID.fromString("3ed1132b-8d61-45d9-bfab-06c1ed96f202");
        given(pythonMonitoringClient.accept(any(), any()))
                .willReturn(new PythonMonitoringJobResponse(jobId, PythonMonitoringJobStatus.ACCEPTED));

        CreateMonitoringRunResponse response = monitoringRunService.create(MonitoringTriggerType.MANUAL);

        assertThat(response.runId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(MonitoringRunStatus.ACCEPTED);
        assertThat(response.triggerType()).isEqualTo(MonitoringTriggerType.MANUAL);
        assertThat(response.totalSourceCount()).isEqualTo(2);
        assertThat(response.requestedAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 10, 0));

        ArgumentCaptor<MonitoringRun> runCaptor = ArgumentCaptor.forClass(MonitoringRun.class);
        verify(monitoringRunRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getPythonJobId()).isEqualTo(jobId.toString());
        assertThat(runCaptor.getValue().getAcceptedAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 10, 0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MonitoringRunSource>> captor = ArgumentCaptor.forClass(List.class);
        verify(monitoringRunSourceRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
                .allSatisfy(runSource -> {
                    assertThat(runSource.getStatus()).isEqualTo(MonitoringRunSourceStatus.PENDING);
                    assertThat(runSource.getProcessingMode()).isEqualTo(MonitoringProcessingMode.NORMAL);
                });
    }

    @Test
    void 활성_소스가_없으면_실행을_생성할_수_없다() {
        given(monitoringSourceRepository.findAllByEnabledTrueOrderByIdAsc()).willReturn(List.of());

        assertThatThrownBy(() -> monitoringRunService.create(MonitoringTriggerType.MANUAL))
                .isInstanceOf(NoActiveMonitoringSourceException.class);

        verifyNoInteractions(monitoringRunRepository, monitoringRunSourceRepository, pythonMonitoringClient);
    }

    @Test
    void Python_작업_접수에_실패하면_실행을_실패_상태로_남긴다() {
        List<MonitoringSource> sources = List.of(createSource("서울시"));
        given(monitoringSourceRepository.findAllByEnabledTrueOrderByIdAsc()).willReturn(sources);
        given(monitoringRunRepository.save(any(MonitoringRun.class))).willAnswer(invocation -> {
            MonitoringRun run = invocation.getArgument(0);
            ReflectionTestUtils.setField(run, "id", 1L);
            return run;
        });
        given(pythonMonitoringClient.accept(any(), any()))
                .willThrow(new PythonMonitoringClientException("연결 실패"));

        assertThatThrownBy(() -> monitoringRunService.create(MonitoringTriggerType.MANUAL))
                .isInstanceOf(MonitoringJobAcceptanceException.class);

        ArgumentCaptor<MonitoringRun> runCaptor = ArgumentCaptor.forClass(MonitoringRun.class);
        verify(monitoringRunRepository).save(runCaptor.capture());
        MonitoringRun failedRun = runCaptor.getValue();
        assertThat(failedRun.getStatus()).isEqualTo(MonitoringRunStatus.FAILED);
        assertThat(failedRun.getCompletedAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 10, 0));
        assertThat(failedRun.getErrorMessage()).isEqualTo("Python 모니터링 작업 접수에 실패했습니다.");
    }

    @Test
    void 실행_이력을_최신순으로_조회한다() {
        MonitoringRun recentRun = createRun(2L, LocalDateTime.of(2026, 8, 12, 10, 0));
        MonitoringRun oldRun = createRun(1L, LocalDateTime.of(2026, 8, 11, 10, 0));
        given(monitoringRunRepository.findAllByOrderByRequestedAtDescIdDesc())
                .willReturn(List.of(recentRun, oldRun));

        List<MonitoringRunSummaryResponse> responses = monitoringRunService.findAll();

        assertThat(responses).extracting(MonitoringRunSummaryResponse::runId)
                .containsExactly(2L, 1L);
        assertThat(responses.getFirst().status()).isEqualTo(MonitoringRunStatus.REQUESTED);
        assertThat(responses.getFirst().totalSourceCount()).isEqualTo(1);
    }

    private MonitoringSource createSource(String organizationName) {
        return MonitoringSource.create(
                organizationName,
                "공지사항",
                null,
                "https://example.com/" + organizationName,
                null,
                3,
                true
        );
    }

    private MonitoringRun createRun(Long id, LocalDateTime requestedAt) {
        MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, requestedAt);
        ReflectionTestUtils.setField(run, "id", id);
        return run;
    }
}
