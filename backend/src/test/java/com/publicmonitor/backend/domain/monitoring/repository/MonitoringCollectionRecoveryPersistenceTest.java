package com.publicmonitor.backend.domain.monitoring.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.publicmonitor.backend.domain.analysis.event.CollectionStoredEvent;
import com.publicmonitor.backend.domain.document.exception.CollectionResultException;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.service.CollectionResultService;
import com.publicmonitor.backend.domain.document.service.DocumentContentNormalizer;
import com.publicmonitor.backend.domain.document.service.DocumentVersionHasher;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.CollectedDocument;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.SourceResult;
import com.publicmonitor.backend.domain.document.web.dto.CollectionSourceStatus;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringCollectionRecoveryService;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringWarningDetails;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:collection-recovery;MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false", "app.telegram.commands.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, CollectionResultService.class, DocumentContentNormalizer.class,
        DocumentVersionHasher.class, MonitoringCollectionRecoveryService.class,
        MonitoringCollectionRecoveryPersistenceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MonitoringCollectionRecoveryPersistenceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 2, 12, 0);
    private static final LocalDateTime CUTOFF = NOW.minusHours(1);
    private static final Set<MonitoringRunStatus> PENDING = EnumSet.of(
            MonitoringRunStatus.REQUESTED, MonitoringRunStatus.ACCEPTED, MonitoringRunStatus.RUNNING);
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MonitoringRunRepository runs;
    @Autowired MonitoringRunSourceRepository sources;
    @Autowired DocumentDetectionRepository detections;
    @Autowired CollectionResultService collection;
    @Autowired MonitoringCollectionRecoveryService recovery;
    @Autowired Events events;

    @TestConfiguration
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ZoneOffset.UTC); }
        @Bean Events events() { return new Events(); }
    }

    static class Events {
        final List<Long> runIds = new CopyOnWriteArrayList<>();
        @TransactionalEventListener
        public void collected(CollectionStoredEvent event) { runIds.add(event.runId()); }
        long count(Long runId) { return runIds.stream().filter(runId::equals).count(); }
    }

    record Fixture(Long runId, Long sourceId, String jobId) {
        CollectionResultRequest request() {
            return new CollectionResultRequest(runId, jobId, List.of(new SourceResult(
                    sourceId, CollectionSourceStatus.COMPLETED, null, List.of(new CollectedDocument(
                    "https://example.org/notice/" + sourceId, null, "지원 사업", "공고 본문", null, List.of())))));
        }
    }

    private Fixture fixture(MonitoringRunStatus status, LocalDateTime acceptedAt) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var source = MonitoringSource.create("기관", "게시판", null,
                    "https://example.org/" + UUID.randomUUID(), null, 1, true);
            em.persist(source);
            var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, CUTOFF.minusHours(1));
            String jobId = UUID.randomUUID().toString();
            if (status != MonitoringRunStatus.REQUESTED) run.accept(jobId, acceptedAt);
            if (status == MonitoringRunStatus.RUNNING) run.start(acceptedAt);
            if (status == MonitoringRunStatus.COLLECTED || status == MonitoringRunStatus.COMPLETED) {
                run.completeCollection(1, 0, 0, 0, NOW);
            }
            if (status == MonitoringRunStatus.COMPLETED) run.completeReport(NOW);
            if (status == MonitoringRunStatus.FAILED) run.completeCollection(0, 1, 0, 1, NOW);
            em.persist(run);
            em.persist(MonitoringRunSource.create(run, source));
            return new Fixture(run.getId(), source.getId(), jobId);
        });
    }

    @Test
    void 수집_중인_만료_실행만_조회하고_접수시각과_경계값을_사용한다() {
        var requested = fixture(MonitoringRunStatus.REQUESTED, CUTOFF);
        var boundary = fixture(MonitoringRunStatus.ACCEPTED, CUTOFF);
        var running = fixture(MonitoringRunStatus.RUNNING, CUTOFF.minusSeconds(1));
        var fresh = fixture(MonitoringRunStatus.ACCEPTED, CUTOFF.plusSeconds(1));
        var collected = fixture(MonitoringRunStatus.COLLECTED, CUTOFF);
        var completed = fixture(MonitoringRunStatus.COMPLETED, CUTOFF);
        var failed = fixture(MonitoringRunStatus.FAILED, CUTOFF);
        var ids = runs.findExpiredCollections(PENDING, CUTOFF, PageRequest.of(0, 100));
        assertThat(ids).contains(requested.runId(), boundary.runId(), running.runId())
                .doesNotContain(fresh.runId(), collected.runId(), completed.runId(), failed.runId());
        for (var protectedRun : List.of(fresh, collected, completed, failed)) {
            assertThat(recovery.expire(protectedRun.runId(), CUTOFF, NOW)).isFalse();
        }
    }

    @Test
    void 만료된_실행과_소스를_실패로_저장하고_늦은_콜백과_중복_복구를_무시한다() {
        var fixture = fixture(MonitoringRunStatus.ACCEPTED, CUTOFF);
        assertThat(recovery.expire(fixture.runId(), CUTOFF, NOW)).isTrue();
        assertThat(recovery.expire(fixture.runId(), CUTOFF, NOW.plusMinutes(1))).isFalse();
        assertThat(collection.receive(fixture.request()).documents()).isEmpty();
        var run = runs.findById(fixture.runId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(MonitoringRunStatus.FAILED);
        assertThat(run.getCompletedAt()).isEqualTo(NOW);
        assertThat(run.getWarningCount()).isEqualTo(1);
        assertThat(run.getFailedSourceCount()).isEqualTo(1);
        var source = sources.findWithSourcesByMonitoringRunId(fixture.runId()).getFirst();
        assertThat(source.getStatus()).isEqualTo(MonitoringRunSourceStatus.FAILED);
        assertThat(MonitoringWarningDetails.read(source).getFirst().message()).contains("대기 시간이 초과");
        assertThat(events.count(fixture.runId())).isZero();
        assertThat(detections.findSummaries(fixture.runId(), null, null, PageRequest.of(0, 10)).getTotalElements()).isZero();
    }

    @Test
    void 콜백이_먼저_완료되면_만료_후보도_잠금_안에서_재검사하고_보존한다() {
        var fixture = fixture(MonitoringRunStatus.RUNNING, CUTOFF);
        assertThat(collection.receive(fixture.request()).documents()).hasSize(1);
        assertThat(recovery.expire(fixture.runId(), CUTOFF, NOW)).isFalse();
        assertThat(collection.receive(fixture.request()).documents()).isEmpty();
        assertThat(events.count(fixture.runId())).isEqualTo(1);
        assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus()).isEqualTo(MonitoringRunStatus.COLLECTED);
    }

    @Test
    void 완료_또는_실패_후_콜백은_상태를_되돌리지_않고_다른_작업_ID는_거부한다() {
        for (var status : List.of(MonitoringRunStatus.COMPLETED, MonitoringRunStatus.FAILED)) {
            var fixture = fixture(status, CUTOFF);
            assertThat(collection.receive(fixture.request()).documents()).isEmpty();
            assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus()).isEqualTo(status);
            assertThat(events.count(fixture.runId())).isZero();
            var wrongJob = new CollectionResultRequest(fixture.runId(), UUID.randomUUID().toString(), fixture.request().sources());
            assertThatThrownBy(() -> collection.receive(wrongJob)).isInstanceOf(CollectionResultException.class);
        }
    }

    @Test
    void 동시에_재전송된_콜백은_문서와_후속_분석_이벤트를_한번만_생성한다() throws Exception {
        var fixture = fixture(MonitoringRunStatus.ACCEPTED, CUTOFF);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> receive = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return collection.receive(fixture.request()).documents().size();
            };
            var first = executor.submit(receive);
            var second = executor.submit(receive);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(1, 0);
        }
        assertThat(events.count(fixture.runId())).isEqualTo(1);
        assertThat(detections.findSummaries(fixture.runId(), null, null, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }

    @Test
    void 만료와_콜백이_동시에_실행돼도_먼저_확정된_결과만_남는다() throws Exception {
        var fixture = fixture(MonitoringRunStatus.ACCEPTED, CUTOFF);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var expiration = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return recovery.expire(fixture.runId(), CUTOFF, NOW);
            });
            var callback = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return collection.receive(fixture.request()).documents().size();
            });
            start.countDown();
            boolean expired = expiration.get(15, TimeUnit.SECONDS);
            assertThat(callback.get(15, TimeUnit.SECONDS)).isEqualTo(expired ? 0 : 1);
            assertThat(events.count(fixture.runId())).isEqualTo(expired ? 0 : 1);
            assertThat(runs.findById(fixture.runId()).orElseThrow().getStatus())
                    .isEqualTo(expired ? MonitoringRunStatus.FAILED : MonitoringRunStatus.COLLECTED);
        }
    }
}
