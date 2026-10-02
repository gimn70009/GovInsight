package com.publicmonitor.backend.domain.monitoring.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.publicmonitor.backend.domain.monitoring.client.*;
import com.publicmonitor.backend.domain.monitoring.client.dto.PythonMonitoringJobResponse;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.monitoring.exception.*;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringRunService;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:run-exclusivity;MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, MonitoringRunService.class, MonitoringRunExclusivityTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MonitoringRunExclusivityTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MonitoringRunService service;
    @Autowired MonitoringRunRepository runs;
    @Autowired MonitoringSourceRepository sources;
    @MockitoBean PythonMonitoringClient python;
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 2, 12, 0);

    @TestConfiguration
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ZoneOffset.UTC); }
    }

    @BeforeEach void setup() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            em.createQuery("delete from MonitoringRunSource").executeUpdate();
            em.createQuery("delete from MonitoringRun").executeUpdate();
            em.createQuery("delete from MonitoringSource").executeUpdate();
            em.persist(MonitoringSource.create("기관", "게시판", null, "https://example.org", null, 1, true));
            em.persist(MonitoringSource.create("비활성 기관", "게시판", null, "https://example.org/disabled", null, 1, false));
        });
    }

    private MonitoringRun existing(MonitoringRunStatus status) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now.minusHours(2));
            if (status != MonitoringRunStatus.REQUESTED) run.accept(UUID.randomUUID().toString(), now.minusHours(2));
            if (status == MonitoringRunStatus.RUNNING) run.start(now.minusHours(2));
            if (status == MonitoringRunStatus.COLLECTED || status == MonitoringRunStatus.COMPLETED) {
                run.completeCollection(1, 0, 1, 0, now);
            }
            if (status == MonitoringRunStatus.COMPLETED) run.completeReport(now);
            if (status == MonitoringRunStatus.FAILED) run.completeCollection(0, 1, 0, 1, now);
            em.persist(run);
            return run;
        });
    }

    @ParameterizedTest
    @EnumSource(value = MonitoringRunStatus.class, names = {"REQUESTED", "ACCEPTED", "RUNNING", "COLLECTED"})
    void 수집부터_보고서_완료까지_추가_접수와_Python호출을_차단한다(MonitoringRunStatus status) {
        var original = existing(status);
        for (var trigger : MonitoringTriggerType.values()) {
            assertThatThrownBy(() -> service.create(trigger)).isInstanceOf(MonitoringAlreadyRunningException.class);
        }
        assertThat(runs.count()).isEqualTo(1);
        verifyNoInteractions(python);
        var activity = service.activity();
        assertThat(activity.running()).isTrue();
        assertThat(activity.runId()).isEqualTo(original.getId());
        assertThat(activity.status()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = MonitoringRunStatus.class, names = {"COMPLETED", "FAILED"})
    void 완료나_실패_후에는_새_실행을_허용한다(MonitoringRunStatus status) {
        existing(status);
        assertThat(service.activity().running()).isFalse();
        when(python.accept(any(), any())).thenReturn(new PythonMonitoringJobResponse(UUID.randomUUID(), PythonMonitoringJobStatus.ACCEPTED));
        var created = service.create(MonitoringTriggerType.MANUAL);
        assertThat(created.totalSourceCount()).isEqualTo(1);
        assertThat(created.status()).isEqualTo(MonitoringRunStatus.ACCEPTED);
        assertThat(runs.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(MonitoringTriggerType.class)
    void 동시에_누르거나_예약과_겹쳐도_한_실행만_생성한다(MonitoringTriggerType secondTrigger) throws Exception {
        var firstAccepted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(python.accept(any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstAccepted.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout");
            }
            return new PythonMonitoringJobResponse(UUID.randomUUID(), PythonMonitoringJobStatus.ACCEPTED);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.create(MonitoringTriggerType.MANUAL));
            assertThat(firstAccepted.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return service.create(secondTrigger);
            });
            try {
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(calls.get()).isEqualTo(1);
            } finally {
                releaseFirst.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(MonitoringRunStatus.ACCEPTED);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(MonitoringAlreadyRunningException.class);
        }
        assertThat(runs.count()).isEqualTo(1);
        verify(python, times(1)).accept(any(), any());
    }

    @Test void 접수실패는_실패로_커밋하고_다음_요청은_막지_않는다() {
        when(python.accept(any(), any())).thenThrow(new PythonMonitoringClientException("offline"))
                .thenReturn(new PythonMonitoringJobResponse(UUID.randomUUID(), PythonMonitoringJobStatus.ACCEPTED));
        assertThatThrownBy(() -> service.create(MonitoringTriggerType.MANUAL)).isInstanceOf(MonitoringJobAcceptanceException.class);
        assertThat(runs.findAll()).singleElement().extracting(MonitoringRun::getStatus).isEqualTo(MonitoringRunStatus.FAILED);
        assertThat(service.activity().running()).isFalse();
        assertThat(service.create(MonitoringTriggerType.MANUAL).status()).isEqualTo(MonitoringRunStatus.ACCEPTED);
    }

    @Test void 첫페이지_밖의_진행중_실행도_감지한다() {
        var active = existing(MonitoringRunStatus.COLLECTED);
        for (int i = 0; i < 12; i++) existing(MonitoringRunStatus.COMPLETED);
        assertThat(service.findAll(0, 8).content()).allMatch(row -> row.status() == MonitoringRunStatus.COMPLETED);
        assertThat(service.activity().runId()).isEqualTo(active.getId());
        assertThatThrownBy(() -> service.create(MonitoringTriggerType.MANUAL)).isInstanceOf(MonitoringAlreadyRunningException.class);
        verifyNoInteractions(python);
    }
}
