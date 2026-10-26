package com.publicmonitor.backend.domain.monitoring.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.publicmonitor.backend.domain.monitoring.client.*;
import com.publicmonitor.backend.domain.monitoring.client.dto.PythonMonitoringJobResponse;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.monitoring.exception.*;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringRunService;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringScheduleQueueService;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringScheduleService;
import com.publicmonitor.backend.domain.monitoring.web.dto.UpdateMonitoringScheduleRequest;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    "spring.datasource.url=jdbc:h2:mem:schedule-queue;MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=true",
    "app.monitoring.schedule.catch-up-window=PT5M"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, MonitoringScheduleQueueService.class, MonitoringRunService.class,
        MonitoringScheduleService.class, MonitoringScheduleQueueIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MonitoringScheduleQueueIntegrationTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate SCHEDULE_DATE = LocalDate.of(2026, 10, 8);
    private static final LocalDateTime SCHEDULED_AT = SCHEDULE_DATE.atTime(14, 0);
    private static final Duration WINDOW = Duration.ofMinutes(5);

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MonitoringScheduleRepository schedules;
    @Autowired MonitoringSourceRepository sources;
    @Autowired MonitoringRunRepository runs;
    @Autowired MonitoringScheduleQueueService queue;
    @Autowired MonitoringScheduleService settings;
    @Autowired MonitoringRunService runService;
    @Autowired ControlledClock clock;
    @MockitoBean PythonMonitoringClient python;

    @TestConfiguration
    static class Config {
        @Bean ControlledClock clock() { return new ControlledClock(); }
    }

    @BeforeEach void setup() {
        clock.reset(SCHEDULED_AT.plusMinutes(2));
        transaction().executeWithoutResult(status -> {
            em.createQuery("delete from MonitoringRunSource").executeUpdate();
            em.createQuery("delete from MonitoringRun").executeUpdate();
            em.createQuery("delete from MonitoringSource").executeUpdate();
            em.createQuery("delete from MonitoringSchedule").executeUpdate();
            em.persist(MonitoringSource.create("기관", "게시판", null, "https://example.org", null, 1, true));
            var schedule = MonitoringSchedule.defaultSchedule();
            schedule.update(true, MonitoringScheduleFrequency.DAILY, LocalTime.of(14, 0), Set.of());
            em.persist(schedule);
        });
        when(python.accept(any(), any())).thenAnswer(invocation -> accepted());
    }

    @Test void manualRunAt1358DefersThe1400ScheduleUntilItCompletesAt1407() {
        clock.set(SCHEDULED_AT.minusMinutes(2));
        var manual = runService.create(MonitoringTriggerType.MANUAL);
        clock.set(SCHEDULED_AT);
        queue.enqueueDue();
        assertThat(queue.startPending()).isEmpty();
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULED_AT);
        clock.set(SCHEDULED_AT.plusMinutes(7));
        complete(manual.runId());
        var scheduled = queue.startPending().orElseThrow();
        assertThat(scheduled.status()).isEqualTo(MonitoringRunStatus.ACCEPTED);
        assertThat(stored().getPendingScheduledAt()).isNull();
        assertThat(queue.startPending()).isEmpty();
        assertThat(runs.findAll()).filteredOn(run -> run.getTriggerType() == MonitoringTriggerType.SCHEDULED)
                .singleElement().extracting(MonitoringRun::getId).isEqualTo(scheduled.runId());
        verify(python, times(2)).accept(any(), any());
    }

    @Test void restartAfterGracePeriodStillStartsPersistedPendingOccurrence() {
        queue.enqueueDue();
        clock.set(SCHEDULED_AT.plusHours(8));
        var restarted = new MonitoringScheduleQueueService(schedules, sources, runs, runService, clock, WINDOW);
        assertThat(restarted.hasWork()).isTrue();
        var response = transaction().execute(status -> restarted.startPending());
        assertThat(response).isPresent();
        assertThat(stored().getPendingScheduledAt()).isNull();
        assertThat(stored().getLastAttemptedDate()).isEqualTo(SCHEDULE_DATE);
        assertThat(queue.startPending()).isEmpty();
        verify(python, times(1)).accept(any(), any());
    }

    @Test void multipleDaysCoalesceIntoEarliestPendingOccurrence() {
        queue.enqueueDue();
        clock.set(SCHEDULED_AT.plusDays(1).plusMinutes(1));
        queue.enqueueDue();
        clock.set(SCHEDULED_AT.plusDays(2).plusMinutes(1));
        queue.enqueueDue();
        queue.enqueueDue();
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(stored().getLastAttemptedDate()).isEqualTo(SCHEDULE_DATE.plusDays(2));
        complete(queue.startPending().orElseThrow().runId());
        queue.enqueueDue();
        assertThat(queue.startPending()).isEmpty();
        assertThat(runs.count()).isEqualTo(1);
        verify(python, times(1)).accept(any(), any());
    }

    @Test void enqueueCommitsIndependentlyOfCallingTransaction() {
        transaction().executeWithoutResult(status -> { queue.enqueueDue(); status.setRollbackOnly(); });
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(stored().getLastAttemptedDate()).isEqualTo(SCHEDULE_DATE);
        queue.enqueueDue();
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULED_AT);
    }

    @Test void unexpectedAcceptanceErrorRollsBackConsumptionAndRunCreation() {
        queue.enqueueDue();
        when(python.accept(any(), any())).thenThrow(new IllegalStateException("unexpected test failure"));
        assertThatThrownBy(queue::startPending).isInstanceOf(IllegalStateException.class);
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(runs.count()).isZero();
        doAnswer(invocation -> accepted()).when(python).accept(any(), any());
        assertThat(queue.startPending()).isPresent();
        assertThat(runs.count()).isEqualTo(1);
    }

    @Test void acceptanceFailureCommitsFailedRunAndClearsPendingTogether() {
        queue.enqueueDue();
        when(python.accept(any(), any())).thenThrow(new PythonMonitoringClientException("offline"));
        assertThatThrownBy(queue::startPending).isInstanceOf(MonitoringJobAcceptanceException.class);
        assertThat(stored().getPendingScheduledAt()).isNull();
        assertThat(stored().getLastAttemptedDate()).isEqualTo(SCHEDULE_DATE);
        assertThat(runs.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(MonitoringRunStatus.FAILED);
            assertThat(run.getTriggerType()).isEqualTo(MonitoringTriggerType.SCHEDULED);
        });
        assertThat(queue.startPending()).isEmpty();
        queue.enqueueDue();
        assertThat(stored().getPendingScheduledAt()).isNull();
        verify(python, times(1)).accept(any(), any());
    }

    @Test void noActiveSourceKeepsPendingUntilASourceIsEnabledAgain() {
        queue.enqueueDue();
        changeSources(false);
        assertThat(queue.startPending()).isEmpty();
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULED_AT);
        verifyNoInteractions(python);
        changeSources(true);
        clock.set(SCHEDULED_AT.plusMinutes(20));
        assertThat(queue.startPending()).isPresent();
        assertThat(stored().getPendingScheduledAt()).isNull();
    }

    @Test void manualCreationCannotPassPendingSchedule() {
        queue.enqueueDue();
        assertThatThrownBy(() -> runService.create(MonitoringTriggerType.MANUAL))
                .isInstanceOf(MonitoringSchedulePendingException.class);
        assertThat(runs.count()).isZero();
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULED_AT);
        verifyNoInteractions(python);
        assertThat(queue.startPending()).isPresent();
    }

    @Test void staleCancellationCannotDeleteANewerPendingOccurrence() {
        queue.enqueueDue();
        assertThat(cancel(SCHEDULED_AT)).isTrue();
        clock.set(SCHEDULED_AT.plusDays(1));
        queue.enqueueDue();
        assertThat(cancel(SCHEDULED_AT)).isFalse();
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULED_AT.plusDays(1));
        assertThat(queue.startPending()).isPresent();
    }

    @Test void cancellationDoesNotReenqueueSameOccurrence() {
        queue.enqueueDue();
        settings.cancelPending(SCHEDULED_AT);
        queue.enqueueDue();
        assertThat(queue.startPending()).isEmpty();
        assertThat(queue.hasWork()).isFalse();
        assertThat(stored().getLastAttemptedDate()).isEqualTo(SCHEDULE_DATE);
        verifyNoInteractions(python);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disablingOrChangingScheduleCancelsPending(boolean moveTime) {
        queue.enqueueDue();
        settings.update(new UpdateMonitoringScheduleRequest(moveTime, MonitoringScheduleFrequency.DAILY,
                moveTime ? LocalTime.of(15, 0) : LocalTime.of(14, 0), Set.of()));
        assertThat(stored().getPendingScheduledAt()).isNull();
        assertThat(queue.startPending()).isEmpty();
        verifyNoInteractions(python);
    }

    @Test void unchangedSettingsSaveKeepsPendingOccurrence() {
        queue.enqueueDue();
        settings.update(new UpdateMonitoringScheduleRequest(true, MonitoringScheduleFrequency.DAILY,
                LocalTime.of(14, 0), Set.of()));
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(queue.startPending()).isPresent();
    }

    @Test void parallelConsumersOnlyCallPythonOnce() throws Exception {
        queue.enqueueDue();
        assertThat(whileConsuming(queue::startPending)).isEmpty();
        assertThat(runs.count()).isEqualTo(1);
        verify(python, times(1)).accept(any(), any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cancellationOrSettingsSaveCommittedFirstPreventsConsumption(boolean changeSettings) throws Exception {
        queue.enqueueDue();
        var result = afterLockedWrite(() -> {
            if (changeSettings) settings.update(new UpdateMonitoringScheduleRequest(false,
                    MonitoringScheduleFrequency.DAILY, LocalTime.of(14, 0), Set.of()));
            else settings.cancelPending(SCHEDULED_AT);
        }, queue::startPending);
        assertThat(result).isEmpty();
        assertThat(runs.count()).isZero();
        assertThat(stored().getPendingScheduledAt()).isNull();
        verifyNoInteractions(python);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void consumptionCommittedFirstMakesCancellationStaleAndPreservesSettings(boolean changeSettings) throws Exception {
        queue.enqueueDue();
        var result = whileConsuming(() -> {
            if (!changeSettings) return cancel(SCHEDULED_AT);
            settings.update(new UpdateMonitoringScheduleRequest(false, MonitoringScheduleFrequency.CUSTOM,
                    LocalTime.of(15, 17), Set.of(DayOfWeek.MONDAY)));
            return true;
        });
        assertThat(result).isEqualTo(changeSettings);
        assertThat(stored().getPendingScheduledAt()).isNull();
        if (changeSettings) {
            assertThat(stored().isEnabled()).isFalse();
            assertThat(stored().getExecutionTime()).isEqualTo(LocalTime.of(15, 17));
            assertThat(stored().getSelectedDays()).containsExactly(DayOfWeek.MONDAY);
        }
        assertThat(runs.count()).isEqualTo(1);
        verify(python, times(1)).accept(any(), any());
    }

    @Test void manualRequestBehindConsumerCannotCreateASecondRun() {
        queue.enqueueDue();
        assertThatThrownBy(() -> whileConsuming(() -> runService.create(MonitoringTriggerType.MANUAL)))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(MonitoringAlreadyRunningException.class);
        assertThat(runs.count()).isEqualTo(1);
        verify(python, times(1)).accept(any(), any());
    }

    @Test void sourceDisableCommittedWhileConsumerWaitsPreservesPending() throws Exception {
        queue.enqueueDue();
        var result = afterLockedWrite(() -> sources.findAllForRunCreation()
                .forEach(source -> source.changeEnabled(false)), queue::startPending);
        assertThat(result).isEmpty();
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(runs.count()).isZero();
        verifyNoInteractions(python);
    }

    @Test void parallelEnqueuesPersistOneOccurrenceAndOnlyOneConsumerStarts() throws Exception {
        var gate = clock.pauseNextRead();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(queue::enqueueDue);
            try {
                await(gate.entered);
                var started = new CountDownLatch(1);
                var second = executor.submit(() -> { started.countDown(); queue.enqueueDue(); });
                try { await(started); assertBlocked(second); }
                finally { gate.release.countDown(); }
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally { gate.release.countDown(); }
        }
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(stored().getLastAttemptedDate()).isEqualTo(SCHEDULE_DATE);
        assertThat(queue.startPending()).isPresent();
        assertThat(queue.startPending()).isEmpty();
        verify(python, times(1)).accept(any(), any());
    }

    @Test void midnightEnqueueRecordsPreviousScheduledDay() {
        settings.update(new UpdateMonitoringScheduleRequest(true, MonitoringScheduleFrequency.CUSTOM,
                LocalTime.of(23, 59), Set.of(DayOfWeek.THURSDAY)));
        clock.set(SCHEDULE_DATE.plusDays(1).atTime(0, 2));
        queue.enqueueDue();
        assertThat(stored().getPendingScheduledAt()).isEqualTo(SCHEDULE_DATE.atTime(23, 59));
        assertThat(stored().getLastAttemptedDate()).isEqualTo(SCHEDULE_DATE);
        assertThat(queue.startPending()).isPresent();
        queue.enqueueDue();
        assertThat(stored().getPendingScheduledAt()).isNull();
    }

    private <T> T afterLockedWrite(Runnable write, Callable<T> contender) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> transaction().executeWithoutResult(status -> {
                write.run(); em.flush(); held.countDown(); await(release);
            }));
            try {
                await(held);
                var started = new CountDownLatch(1);
                var waiting = executor.submit(() -> { started.countDown(); return contender.call(); });
                try { await(started); assertBlocked(waiting); }
                finally { release.countDown(); }
                writer.get(10, TimeUnit.SECONDS);
                return waiting.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }

    private <T> T whileConsuming(Callable<T> contender) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(python.accept(any(), any())).thenAnswer(invocation -> {
            entered.countDown(); await(release); return accepted();
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var consumer = executor.submit(queue::startPending);
            try {
                await(entered);
                var started = new CountDownLatch(1);
                var waiting = executor.submit(() -> { started.countDown(); return contender.call(); });
                try { await(started); assertBlocked(waiting); }
                finally { release.countDown(); }
                assertThat(consumer.get(10, TimeUnit.SECONDS)).isPresent();
                return waiting.get(10, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }

    private TransactionTemplate transaction() { return new TransactionTemplate(transactions); }
    private MonitoringSchedule stored() { return schedules.findAll().getFirst(); }
    private boolean cancel(LocalDateTime expectedAt) {
        return Boolean.TRUE.equals(transaction().execute(status ->
                schedules.findAllForUpdate().getFirst().cancelPending(expectedAt)));
    }
    private void complete(Long id) {
        transaction().executeWithoutResult(status -> {
            var run = runs.findById(id).orElseThrow();
            var now = LocalDateTime.now(clock.withZone(SEOUL));
            run.completeCollection(1, 0, 0, 0, now);
            run.completeReport(now);
        });
    }
    private void changeSources(boolean enabled) {
        transaction().executeWithoutResult(status -> sources.findAllForRunCreation()
                .forEach(source -> source.changeEnabled(enabled)));
    }
    private static PythonMonitoringJobResponse accepted() {
        return new PythonMonitoringJobResponse(UUID.randomUUID(), PythonMonitoringJobStatus.ACCEPTED);
    }
    private static void assertBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }

    private static class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    static class ControlledClock extends Clock {
        private final AtomicReference<Instant> current;
        private final AtomicReference<Gate> nextGate;
        private final ZoneId zone;

        ControlledClock() {
            this(new AtomicReference<>(Instant.EPOCH), new AtomicReference<>(), ZoneOffset.UTC);
        }

        private ControlledClock(AtomicReference<Instant> current, AtomicReference<Gate> nextGate, ZoneId zone) {
            this.current = current;
            this.nextGate = nextGate;
            this.zone = zone;
        }

        void reset(LocalDateTime value) {
            var previous = nextGate.getAndSet(null);
            if (previous != null) previous.release.countDown();
            set(value);
        }

        void set(LocalDateTime value) { current.set(value.atZone(SEOUL).toInstant()); }

        Gate pauseNextRead() {
            var gate = new Gate();
            nextGate.set(gate);
            return gate;
        }

        @Override public ZoneId getZone() { return zone; }

        @Override public Clock withZone(ZoneId newZone) {
            return new ControlledClock(current, nextGate, newZone);
        }

        @Override public Instant instant() {
            var gate = nextGate.getAndSet(null);
            if (gate != null) {
                gate.entered.countDown();
                await(gate.release);
            }
            return current.get();
        }
    }
}
