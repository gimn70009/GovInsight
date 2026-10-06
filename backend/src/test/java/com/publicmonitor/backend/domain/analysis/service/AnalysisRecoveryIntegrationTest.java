package com.publicmonitor.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.publicmonitor.backend.domain.analysis.client.PythonAnalysisClient;
import com.publicmonitor.backend.domain.analysis.client.dto.PythonAnalysisJobRequest;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisTask;
import com.publicmonitor.backend.domain.analysis.event.*;
import com.publicmonitor.backend.domain.analysis.repository.*;
import com.publicmonitor.backend.domain.analysis.web.dto.*;
import com.publicmonitor.backend.domain.analysis.exception.AnalysisResultException;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
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
    "spring.datasource.url=jdbc:h2:mem:analysis-recovery;MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false", "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, AnalysisTaskService.class, AnalysisTaskRunner.class, AnalysisResultService.class,
    AnalysisJobEventListener.class, AnalysisRecoveryScheduler.class, AnalysisRecoveryIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisRecoveryIntegrationTest {
    @Autowired EntityManager em;
    @Autowired MonitoringRunRepository runs;
    @Autowired AnalysisTaskRepository tasks;
    @Autowired MonitoringReportRepository reports;
    @Autowired AnalysisTaskService service;
    @Autowired AnalysisTaskRunner runner;
    @Autowired AnalysisResultService results;
    @Autowired AnalysisRecoveryScheduler recovery;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MutableClock clock;
    @Autowired ObjectMapper mapper;
    @MockitoBean PythonAnalysisClient client;
    @MockitoBean AnalysisJobRequestService requests;

    static class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-06T00:00:00Z");
        void advance(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
        LocalDateTime now() { return LocalDateTime.now(withZone(ZoneId.of("Asia/Seoul"))); }
    }
    @TestConfiguration static class Config {
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
    @BeforeEach void clean() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            tasks.deleteAllInBatch(); reports.deleteAllInBatch(); runs.deleteAllInBatch();
        });
        reset(client, requests);
    }
    Long collected() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, clock.now());
            run.completeCollection(1, 0, 1, 0, clock.now()); em.persist(run); em.flush(); return run.getId();
        });
    }
    Long queued() {
        Long id = collected();
        String json = "{\"runId\":" + id + ",\"documents\":[{\"title\":\"공고\",\"contentText\":\"본문\"}]}";
        var request = mapper.readValue(json, PythonAnalysisJobRequest.class);
        when(requests.prepareForRecovery(id)).thenReturn(Optional.of(request));
        service.enqueue(id);
        return id;
    }
    AnalysisTask task(Long id) { return tasks.findByRunId(id).orElseThrow(); }
    AnalysisResultRequest analysis(Long id, String token) {
        return new AnalysisResultRequest(id, UUID.fromString(token), List.of(), List.of());
    }
    ProposalResultRequest proposal(Long id, String token) {
        return new ProposalResultRequest(id, UUID.fromString(token), List.of());
    }

    @Test void eventPersistsTaskAndRollbackNeverDispatches() {
        Long id = collected();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            events.publishEvent(new CollectionStoredEvent(id)); tx.setRollbackOnly();
        });
        assertThat(tasks.findByRunId(id)).isEmpty();
        verifyNoInteractions(client);
        when(requests.prepareForRecovery(id)).thenReturn(Optional.empty());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> events.publishEvent(new CollectionStoredEvent(id)));
        assertThat(task(id).getState()).isEqualTo(AnalysisTask.State.DONE);
        verifyNoInteractions(client);
    }
    @Test void lostDispatchAndOrphanAreRecovered() {
        Long id = collected();
        when(requests.prepareForRecovery(id)).thenReturn(Optional.of(mapper.readValue(
                "{\"runId\":" + id + ",\"documents\":[{\"title\":\"공고\"}]}", PythonAnalysisJobRequest.class)));
        recovery.recover();
        assertThat(task(id).getAttemptCount()).isEqualTo(1);
        verify(client).accept(any());
    }
    @Test void threeLostAttemptsReleaseRunAndRejectLateCallbacks() {
        Long id = queued();
        String token = null;
        for (int i = 0; i < 3; i++) {
            token = service.claim(id).orElseThrow().token();
            clock.advance(1801);
        }
        assertThat(service.claim(id)).isEmpty();
        assertThat(task(id).getState()).isEqualTo(AnalysisTask.State.FAILED);
        assertThat(task(id).getRequestJson()).isNull();
        assertThat(runs.findById(id).orElseThrow().getStatus()).isEqualTo(MonitoringRunStatus.FAILED);
        String stale = token;
        assertThatThrownBy(() -> results.receive(analysis(id, stale))).isInstanceOf(AnalysisResultException.class);
        assertThatThrownBy(() -> results.receiveProposal(proposal(id, stale))).isInstanceOf(AnalysisResultException.class);
    }
    @Test void acceptedAnalysisThenLostProposalRecoversAndFencesOldAttempt() {
        Long id = queued();
        var first = service.claim(id).orElseThrow();
        results.receive(analysis(id, first.token()));
        assertThat(task(id).getState()).isEqualTo(AnalysisTask.State.ANALYZED);
        var deadline = task(id).getAvailableAt();
        clock.advance(10);
        results.receive(analysis(id, first.token()));
        assertThat(task(id).getAvailableAt()).isEqualTo(deadline);
        clock.advance(1801);
        assertThatThrownBy(() -> results.receiveProposal(proposal(id, first.token())))
                .isInstanceOf(AnalysisResultException.class);
        var second = service.claim(id).orElseThrow();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThatThrownBy(() -> results.receive(analysis(id, first.token())))
                .isInstanceOf(AnalysisResultException.class);
        assertThatThrownBy(() -> results.receiveProposal(proposal(id, second.token())))
                .isInstanceOf(AnalysisResultException.class);
        results.receive(analysis(id, second.token()));
        results.receiveProposal(proposal(id, second.token()));
        results.receiveProposal(proposal(id, second.token()));
        assertThat(task(id).getState()).isEqualTo(AnalysisTask.State.DONE);
        assertThat(task(id).getRequestJson()).isNull();
    }
    @Test void acceptanceFailureHasBoundedRetryWithoutLeaseWait() {
        Long id = queued();
        doThrow(new IllegalStateException("offline")).when(client).accept(any());
        for (int i = 0; i < 3; i++) { runner.run(id); clock.advance(11); }
        assertThat(task(id).getState()).isEqualTo(AnalysisTask.State.FAILED);
        verify(client, times(3)).accept(any());
    }
    @Test void concurrentClaimsDispatchOnlyOneAttempt() throws Exception {
        Long id = queued();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Optional<AnalysisTaskService.Work>> work = () -> { gate.await(); return service.claim(id); };
            var a = executor.submit(work); var b = executor.submit(work); gate.countDown();
            int claimed = (a.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                    + (b.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertThat(claimed).isEqualTo(1);
            assertThat(task(id).getAttemptCount()).isEqualTo(1);
        }
    }
    @Test void alreadyStoredInputSurvivesPreparationServiceFailure() {
        Long id = queued();
        service.claim(id).orElseThrow();
        when(requests.prepareForRecovery(id)).thenThrow(new IllegalStateException("unavailable"));
        clock.advance(1801);
        assertThat(service.claim(id)).isPresent();
        verify(requests, times(1)).prepareForRecovery(id);
    }
    @Test void preparationFailureAlsoExhaustsAttempts() {
        Long id = collected();
        service.enqueue(id);
        when(requests.prepareForRecovery(id)).thenThrow(new IllegalArgumentException("invalid stored input"));
        for (int i = 0; i < 3; i++) { assertThat(service.claim(id)).isEmpty(); clock.advance(11); }
        assertThat(task(id).getState()).isEqualTo(AnalysisTask.State.FAILED);
    }
}
