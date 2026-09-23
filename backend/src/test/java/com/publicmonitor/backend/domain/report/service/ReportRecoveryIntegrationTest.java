package com.publicmonitor.backend.domain.report.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.client.PythonReportClient;
import com.publicmonitor.backend.domain.report.entity.*;
import com.publicmonitor.backend.domain.report.repository.*;
import com.publicmonitor.backend.domain.report.web.dto.*;
import com.publicmonitor.backend.domain.report.event.*;
import com.publicmonitor.backend.domain.analysis.event.CollectionStoredEvent;
import com.publicmonitor.backend.domain.analysis.service.AnalysisJobRequestService;
import com.publicmonitor.backend.domain.email.EmailReportDeliveryService;
import com.publicmonitor.backend.domain.telegram.TelegramReportDeliveryService;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:report-recovery;MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false", "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ReportTaskService.class, ReportTaskRunner.class, ReportResultService.class,
    ReportPreparationService.class, ReportJobEventListener.class, ReportDeliveryAttemptService.class, ReportRecoveryIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReportRecoveryIntegrationTest {
    @Autowired EntityManager em;
    @Autowired MonitoringRunRepository runs;
    @Autowired MonitoringReportRepository reports;
    @Autowired ReportTaskRepository tasks;
    @Autowired ReportTaskService service;
    @Autowired ReportTaskRunner runner;
    @Autowired ReportResultService results;
    @Autowired ReportPreparationService preparation;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MutableClock clock;
    @MockitoBean PythonReportClient client;
    @MockitoBean EmailReportDeliveryService email;
    @MockitoBean TelegramReportDeliveryService telegram;
    @MockitoBean AnalysisJobRequestService analysis;
    @MockitoBean ReportJobRequestService requests;

    static class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-09-22T00:00:00Z");
        Duration step = Duration.ZERO;
        void advance(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { var current = instant; instant = instant.plus(step); return Clock.fixed(current, zone); }
        @Override public Instant instant() { return instant; }
        LocalDateTime now() { return LocalDateTime.now(withZone(ZoneId.of("Asia/Seoul"))); }
    }
    @TestConfiguration static class Config {
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
    @BeforeEach void clean() {
        clock.step = Duration.ZERO;
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            tasks.deleteAllInBatch(); reports.deleteAllInBatch(); runs.deleteAllInBatch();
        });
        reset(client, email, telegram, analysis, requests);
    }
    Long runOnly() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, clock.now());
            run.completeCollection(1, 0, 1, 0, clock.now()); em.persist(run); em.flush(); return run.getId();
        });
    }
    Long queued(String payload) {
        Long runId = runOnly();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var report = MonitoringReport.pending(runs.findById(runId).orElseThrow());
            report.saveMinimum("기본 제목", "기본 보고서와 원문 링크"); em.persist(report);
            String input = payload == null ? "{\"runId\":" + runId + ",\"totalSourceCount\":1,\"detectedDocumentCount\":1,\"warningCount\":0,\"documents\":[{\"title\":\"공고\"}]}" : payload;
            if (payload == null) assertThatCode(() -> new ObjectMapper().readValue(input,
                    com.publicmonitor.backend.domain.report.client.dto.PythonReportJobRequest.class)).doesNotThrowAnyException();
            em.persist(ReportTask.pending(report, input, clock.now()));
        });
        return runId;
    }
    ReportTask task(Long id) {
        return new TransactionTemplate(transactions).execute(tx -> tasks.lockByRunId(id).orElseThrow());
    }
    ReportResultRequest success(Long id, String token) {
        return new ReportResultRequest(id, UUID.fromString(token), ReportResultStatus.COMPLETED, "보강 제목", "보강된 제출 안내", null);
    }
    @Test void minimumAndInputPersistBeforeAnyExternalRequest() {
        Long id = queued(null);
        assertThat(reports.findByMonitoringRunId(id).orElseThrow().getSummary()).contains("기본 보고서");
        assertThat(task(id).getRequestJson()).contains("공고");
        verifyNoInteractions(client, email, telegram);
        var work = service.claim(id).orElseThrow();
        assertThat(work.request().jobId().toString()).isEqualTo(work.token());
        assertThat(service.claim(id)).isEmpty();
    }
    @Test void lostCallbacksExhaustThreeAttemptsThenCompleteMinimum() {
        Long id = queued(null);
        for (int i = 0; i < 3; i++) {
            assertThat(service.claim(id).orElseThrow().delivery()).isFalse(); clock.advance(121);
        }
        var delivery = service.claim(id).orElseThrow();
        assertThat(delivery.delivery()).isTrue();
        assertThat(task(id).getAttemptCount()).isEqualTo(3);
        assertThat(task(id).getRequestJson()).isNull();
        assertThat(reports.findByMonitoringRunId(id).orElseThrow().getSummary()).contains("기본 보고서");
        assertThat(runs.findById(id).orElseThrow().getStatus()).isEqualTo(MonitoringRunStatus.COMPLETED);
    }
    @Test void failedModelCompletesMinimumAndLateSuccessCannotOverwriteOrResend() {
        Long id = queued(null);
        var work = service.claim(id).orElseThrow();
        results.receive(new ReportResultRequest(id, UUID.fromString(work.token()), ReportResultStatus.FAILED, null, null, "template error"));
        assertThat(task(id).getState()).isEqualTo(ReportTaskState.DELIVERY_PENDING);
        runner.run(id);
        assertThat(task(id).getState()).isEqualTo(ReportTaskState.DONE);
        assertThat(results.receive(success(id, work.token())).duplicate()).isTrue();
        runner.run(id);
        verify(email, times(1)).deliver(id); verify(telegram, times(1)).deliver(id);
        assertThat(reports.findByMonitoringRunId(id).orElseThrow().getSummary()).contains("기본 보고서");
    }
    @Test void onlyCurrentAttemptCanEnrichReportAndDuplicateCallbackIsIgnored() {
        Long id = queued(null);
        var first = service.claim(id).orElseThrow(); clock.advance(121);
        var second = service.claim(id).orElseThrow();
        assertThat(results.receive(success(id, first.token())).duplicate()).isTrue();
        assertThat(reports.findByMonitoringRunId(id).orElseThrow().getStatus()).isEqualTo(MonitoringReportStatus.PENDING);
        assertThat(results.receive(success(id, second.token())).duplicate()).isFalse();
        assertThat(results.receive(success(id, second.token())).duplicate()).isTrue();
        assertThat(reports.findByMonitoringRunId(id).orElseThrow().getSummary()).isEqualTo("보강된 제출 안내");
    }
    @Test void unavailablePythonIsRetriedThenMinimumIsDelivered() {
        Long id = queued(null);
        when(client.accept(any())).thenThrow(new IllegalStateException("unavailable"));
        for (int i = 0; i < 3; i++) { runner.run(id); clock.advance(11); }
        runner.run(id);
        verify(client, times(3)).accept(any()); verify(email).deliver(id); verify(telegram).deliver(id);
        assertThat(task(id).getState()).isEqualTo(ReportTaskState.DONE);
    }
    @Test void abandonedDeliveryLeaseCanBeRecoveredAfterRestart() {
        Long id = queued(null);
        var work = service.claim(id).orElseThrow(); results.receive(success(id, work.token()));
        var abandoned = service.claim(id).orElseThrow();
        assertThat(abandoned.delivery()).isTrue();
        assertThat(service.claim(id)).isEmpty();
        clock.advance(121);
        runner.run(id);
        assertThat(task(id).getState()).isEqualTo(ReportTaskState.DONE);
        verify(email).deliver(id);
    }
    @Test void oneDeliveryChannelFailureDoesNotBlockOtherAndRemainsRecoverable() {
        Long id = queued(null);
        var work = service.claim(id).orElseThrow(); results.receive(success(id, work.token()));
        doThrow(new IllegalStateException("DB unavailable")).doNothing().when(email).deliver(id);
        runner.run(id);
        verify(telegram).deliver(id);
        assertThat(task(id).getState()).isEqualTo(ReportTaskState.DELIVERY_PENDING);
        clock.advance(61); runner.run(id);
        assertThat(task(id).getState()).isEqualTo(ReportTaskState.DONE);
    }
    @Test void invalidPersistedPayloadStillCompletesMinimum() {
        Long id = queued("null");
        assertThat(service.claim(id).orElseThrow().delivery()).isTrue();
        assertThat(reports.findByMonitoringRunId(id).orElseThrow().getStatus()).isEqualTo(MonitoringReportStatus.COMPLETED);
    }
    @Test void concurrentClaimersCannotDispatchSameGenerationTwice() throws Exception {
        Long id = queued(null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(1);
            Callable<Boolean> call = () -> { ready.await(); return service.claim(id).isPresent(); };
            var first = pool.submit(call); var second = pool.submit(call); ready.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(task(id).getAttemptCount()).isEqualTo(1);
    }
    @Test void proposalCompletionPersistsTaskInSameTransactionWithoutWaitingForAfterCommit() {
        Long id = runOnly();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> events.publishEvent(new ProposalCompletedEvent(id)));
        assertThat(task(id).getState()).isEqualTo(ReportTaskState.DELIVERY_PENDING);
        assertThat(reports.findByMonitoringRunId(id).orElseThrow().getSummary()).contains("게시글이 없습니다");
        verify(requests).request(id);
    }
    @Test void reusedAnalysisPathAlsoPersistsTaskBeforeCommit() {
        Long id = runOnly(); when(analysis.prepare(id)).thenReturn(Optional.empty());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> events.publishEvent(new CollectionStoredEvent(id)));
        assertThat(task(id).getState()).isEqualTo(ReportTaskState.DELIVERY_PENDING);
    }
    @Test void rollbackDoesNotLeaveAnOrphanMinimumOrTask() {
        Long id = runOnly();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { preparation.enqueue(id); tx.setRollbackOnly(); });
        assertThat(reports.findByMonitoringRunId(id)).isEmpty(); assertThat(tasks.count()).isZero();
    }
    @Test void legacyPendingReportWithoutTaskCanBeQueued() {
        Long id = runOnly();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> em.persist(MonitoringReport.pending(runs.findById(id).orElseThrow())));
        assertThat(reports.findUnqueued(org.springframework.data.domain.PageRequest.of(0, 20))).contains(id);
        preparation.prepare(id);
        assertThat(task(id).getState()).isEqualTo(ReportTaskState.DELIVERY_PENDING);
    }
    @Test void dueQueryResumesExpiredWorkButExcludesActiveAndFinishedWork() {
        Long id = queued(null);
        var page = org.springframework.data.domain.PageRequest.of(0, 20);
        assertThat(tasks.findDue(clock.now(), page)).contains(id);
        var work = service.claim(id).orElseThrow();
        assertThat(tasks.findDue(clock.now(), page)).doesNotContain(id);
        clock.advance(121);
        assertThat(tasks.findDue(clock.now(), page)).contains(id);
        results.receive(success(id, work.token()));
        runner.run(id);
        assertThat(tasks.findDue(clock.now(), page)).doesNotContain(id);
    }
    @Test void advancingWallClockDoesNotPreventImmediateMinimumFinalization() {
        Long id = queued("null");
        clock.step = Duration.ofMillis(1);
        assertThat(service.claim(id).orElseThrow().delivery()).isTrue();
        assertThat(reports.findByMonitoringRunId(id).orElseThrow().getStatus()).isEqualTo(MonitoringReportStatus.COMPLETED);
    }

}
