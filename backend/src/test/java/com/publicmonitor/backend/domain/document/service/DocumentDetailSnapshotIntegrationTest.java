package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.entity.*;
import com.publicmonitor.backend.domain.document.repository.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:detailsnapshot;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, DocumentDetailSnapshotIntegrationTest.Config.class, DocumentDetailSnapshotEvents.class})
class DocumentDetailSnapshotIntegrationTest {
    @org.springframework.boot.test.context.TestConfiguration static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean Clock clock() { return Clock.system(ZoneId.of("Asia/Seoul")); }
    }
    @Autowired EntityManager em;
    @Autowired DocumentDetailSnapshotRepository snapshots;
    @Autowired DocumentVersionRepository versions;
    @Autowired DocumentAttachmentRepository attachments;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private DocumentDetailSnapshotStore store() { return new DocumentDetailSnapshotStore(snapshots, versions, mapper, clock); }
    private DocumentDetailSnapshotReader reader() { return new DocumentDetailSnapshotReader(snapshots, mapper); }
    private Long version() {
        var source = MonitoringSource.create("기관", "공고", null, "https://example.org/" + java.util.UUID.randomUUID(), null, 2, true);
        em.persist(source);
        var doc = Document.create(source, "https://example.org/notice/" + java.util.UUID.randomUUID(), "1", now()); em.persist(doc);
        var version = DocumentVersion.create(doc, 1, "지원사업", "저장된 원문", "a".repeat(64), now(), 0, now()); em.persist(version);
        em.flush(); return version.getId();
    }
    private DocumentDetailPayload ready(String name) {
        return new DocumentDetailPayload(List.of(new ResponsibleOrganization(name, "주관기관", "NOTICE_BODY", null, name)),
                new SubmissionGuide(List.of(), List.of(), "READY"),
                new ParticipationGuide(List.of(), List.of(), List.of(), List.of(), "PARTIAL"), null);
    }
    @Test void missingSnapshotReadsNeverEnqueueOrWrite() {
        Long id = version();
        assertThat(reader().read(id).participationGuide().rewriteStatus()).isEqualTo("PENDING");
        assertThat(reader().read(id).submissionGuide().notesStatus()).isEqualTo("PENDING");
        assertThat(snapshots.count()).isZero();
        assertThat(snapshots.findMissing(PageRequest.of(0, 10))).contains(id);
    }
    @Test void persistedPayloadSurvivesRestartAndMetadataReadDoesNotLoadRawText() {
        Long id = version();
        var file = DocumentAttachment.create(versions.getReferenceById(id), "공고.pdf", "https://example.org/file", "pdf", "application/pdf",
                10L, "b".repeat(64), "원문".repeat(10000), AttachmentParseStatus.COMPLETED, null); em.persist(file);
        var store = store(); store.enqueueMissing(id); var claim = store.claim(id);
        assertThat(store.complete(claim, ready("가람진흥원"))).isTrue(); em.flush(); em.clear();
        assertThat(new DocumentDetailSnapshotReader(snapshots, mapper).read(id)).isEqualTo(ready("가람진흥원"));
        assertThat(reader().read(id)).isEqualTo(ready("가람진흥원"));
        assertThat(attachments.findMetadataByDocumentVersionId(id)).singleElement().satisfies(a -> assertThat(a.getFileName()).isEqualTo("공고.pdf"));
        assertThat(snapshots.findDue(now(), DocumentDetailSnapshotStore.POLICY, PageRequest.of(0, 10))).isEmpty();
    }
    @Test void sourceChangeCannotBeOverwrittenByAnOlderBuilder() {
        Long id = version(); var store = store(); store.enqueueMissing(id); var old = store.claim(id); em.flush();
        snapshots.invalidate(id, now()); em.clear();
        var current = store.claim(id); em.flush();
        assertThat(store.complete(old, ready("이전 결과"))).isFalse();
        assertThat(store.complete(current, ready("새 결과"))).isTrue(); em.clear();
        assertThat(reader().read(id)).isEqualTo(ready("새 결과"));
    }
    @Test void paidResultFinishesInvalidateInSameTransactionAndStaleResultsDoNot() {
        Long id = version(); var store = store(); store.enqueueMissing(id);
        store.complete(store.claim(id), ready("기존 결과")); em.flush(); em.clear();
        assertThat(versions.claimParticipationRewrite(id, "current", null, "pending")).isEqualTo(1);
        var writes = new ParticipationRewriteStore(versions, snapshots);
        writes.finish(id, "old", "stale"); em.clear();
        assertThat(snapshots.findById(id).orElseThrow().getState()).isEqualTo(DocumentDetailSnapshot.State.READY);
        writes.finish(id, "current", "ready"); em.clear();
        assertThat(snapshots.findById(id).orElseThrow().getState()).isEqualTo(DocumentDetailSnapshot.State.QUEUED);
        assertThat(reader().read(id).responsibleOrganizations().getFirst().name()).isEqualTo("기존 결과");
        assertThat(reader().read(id).participationGuide().rewriteStatus()).isEqualTo("PENDING");
        assertThat(versions.claimSubmissionNotes(id, "notes", null, "pending")).isEqualTo(1);
        long revision = snapshots.findById(id).orElseThrow().getRevision();
        new SubmissionNotesStore(versions, snapshots).finish(id, "notes", "ready"); em.clear();
        assertThat(snapshots.findById(id).orElseThrow().getRevision()).isEqualTo(revision + 1);
    }
    @Test void crashesAndFailuresAreBoundedAndDoNotLeaveTheBrowserPollingForever() {
        Long id = version(); var store = store(); store.enqueueMissing(id);
        for (int attempt = 0; attempt < 3; attempt++) {
            var row = snapshots.findById(id).orElseThrow();
            ReflectionTestUtils.setField(row, "availableAt", now().minusSeconds(1)); em.flush();
            var claim = store.claim(id); assertThat(claim).isNotNull(); em.flush();
            store.fail(claim); em.clear();
        }
        assertThat(snapshots.findById(id).orElseThrow().getState()).isEqualTo(DocumentDetailSnapshot.State.FAILED);
        assertThat(store.claim(id)).isNull();
        assertThat(reader().read(id).participationGuide().rewriteStatus()).isEqualTo("UNAVAILABLE");
    }
    @Test void lostModelCallbackRefreshesStoredPendingAfterDeadline() {
        Long id = version(); var store = store(); store.enqueueMissing(id);
        store.complete(store.claim(id), DocumentDetailPayload.empty("PENDING")); em.flush(); em.clear();
        assertThat(snapshots.findDue(now(), DocumentDetailSnapshotStore.POLICY, PageRequest.of(0, 10))).isEmpty();
        assertThat(snapshots.findDue(now().plusMinutes(4), DocumentDetailSnapshotStore.POLICY, PageRequest.of(0, 10))).contains(id);
    }
    @Test void pendingRegenerationKeepsPreviouslyStoredSectionsUntilReplacementIsReady() {
        Long id = version(); var store = store(); store.enqueueMissing(id);
        var rule = new ParticipationRule("BASIC", "자격", "기존 신청 조건", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of());
        var old = new DocumentDetailPayload(List.of(),
                new SubmissionGuide(List.of(), List.of(new SubmissionNote("기존 제출 안내", null, null)), "READY"),
                new ParticipationGuide(List.of(), List.of(rule), List.of(), List.of(), "PARTIAL"), null);
        store.complete(store.claim(id), old); em.flush(); em.clear();
        snapshots.invalidate(id, now()); em.clear();
        store.complete(store.claim(id), DocumentDetailPayload.empty("PENDING")); em.clear();
        var stored = reader().read(id);
        assertThat(stored.participationGuide().rules()).containsExactly(rule);
        assertThat(stored.submissionGuide().notes()).isEqualTo(old.submissionGuide().notes());
        assertThat(stored.participationGuide().rewriteStatus()).isEqualTo("PENDING");
        assertThat(snapshots.findDue(now().plusMinutes(4), DocumentDetailSnapshotStore.POLICY, PageRequest.of(0, 10))).contains(id);
    }
    @Test void corruptStoredDisplayReturnsTerminalStateWithoutRunningAJob() {
        Long id = version(); var store = store(); store.enqueueMissing(id);
        var claim = store.claim(id); em.flush();
        snapshots.complete(id, claim.revision(), claim.token(), "{broken", now().plusYears(1)); em.clear();
        assertThat(reader().read(id).submissionGuide().notesStatus()).isEqualTo("UNAVAILABLE");
        assertThat(snapshots.findById(id).orElseThrow().getState()).isEqualTo(DocumentDetailSnapshot.State.READY);
    }
    @Test void expiredWorkerLeaseIsRecoveredButRepeatedCrashesStop() {
        Long id = version(); var store = store(); store.enqueueMissing(id);
        var first = store.claim(id); em.flush();
        assertThat(store.claim(id)).isNull();
        for (int attempt = 1; attempt <= 3; attempt++) {
            var later = new DocumentDetailSnapshotStore(snapshots, versions, mapper,
                    Clock.offset(clock, Duration.ofMinutes(attempt * 4L)));
            assertThat(snapshots.findDue(now().plusMinutes(attempt * 4L), DocumentDetailSnapshotStore.POLICY,
                    PageRequest.of(0, 10))).contains(id);
            if (attempt < 3) assertThat(later.claim(id)).isNotNull();
            else assertThat(later.claim(id)).isNull();
            em.flush();
        }
        assertThat(store.complete(first, ready("만료된 작업"))).isFalse();
        em.clear();
        assertThat(snapshots.findById(id).orElseThrow().getState()).isEqualTo(DocumentDetailSnapshot.State.FAILED);
    }
    @ParameterizedTest
    @ValueSource(strings = {"COLLECTION", "ANALYSIS", "PROPOSAL"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sourceEventsQueueOnlyWhenTheirTransactionCommits(String kind) {
        var tx = new TransactionTemplate(transactions);
        var fixture = tx.execute(status -> {
            Long id = version(); var version = versions.findById(id).orElseThrow();
            var run = com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun.create(
                    com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType.MANUAL, 1, now()); em.persist(run);
            var runSource = com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource.create(run,
                    version.getDocument().getMonitoringSource()); em.persist(runSource);
            var detection = DocumentDetection.create(runSource, version.getDocument(), version, DocumentChangeType.NEW_DOCUMENT, now());
            em.persist(detection);
            var store = store(); store.enqueueMissing(id); store.complete(store.claim(id), ready("기존 결과"));
            return new Long[]{id, detection.getId(), runSource.getId(), run.getId(), version.getDocument().getId(),
                    version.getDocument().getMonitoringSource().getId()};
        });
        try {
            Object event = switch (kind) {
                case "COLLECTION" -> new com.publicmonitor.backend.domain.analysis.event.CollectionStoredEvent(fixture[3]);
                case "ANALYSIS" -> new com.publicmonitor.backend.domain.analysis.event.AnalysisStoredEvent(fixture[3]);
                default -> new com.publicmonitor.backend.domain.report.event.ProposalCompletedEvent(fixture[3]);
            };
            tx.executeWithoutResult(status -> { events.publishEvent(event); status.setRollbackOnly(); });
            assertThat(snapshots.findById(fixture[0]).orElseThrow().getState()).isEqualTo(DocumentDetailSnapshot.State.READY);
            tx.executeWithoutResult(status -> {
                events.publishEvent(event);
                assertThat(snapshots.findById(fixture[0]).orElseThrow().getState()).isEqualTo(DocumentDetailSnapshot.State.READY);
            });
            var stored = snapshots.findById(fixture[0]).orElseThrow();
            assertThat(stored.getState()).isEqualTo(DocumentDetailSnapshot.State.QUEUED);
            assertThat(stored.getRevision()).isEqualTo(1);
            assertThat(reader().read(fixture[0]).responsibleOrganizations().getFirst().name()).isEqualTo("기존 결과");
        } finally {
            tx.executeWithoutResult(status -> {
                em.remove(em.find(DocumentDetailSnapshot.class, fixture[0]));
                em.remove(em.find(DocumentDetection.class, fixture[1]));
                em.remove(em.find(com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource.class, fixture[2]));
                em.remove(em.find(com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun.class, fixture[3]));
                em.remove(em.find(DocumentVersion.class, fixture[0]));
                em.remove(em.find(Document.class, fixture[4]));
                em.remove(em.find(MonitoringSource.class, fixture[5]));
            });
        }
    }
}
