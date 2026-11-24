package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.repository.DocumentDetailSnapshotRepository;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j @Component
@ConditionalOnProperty(name = "app.document-detail.worker.enabled", matchIfMissing = true)
public class DocumentDetailSnapshotWorker {
    private final DocumentDetailSnapshotRepository snapshots;
    private final DocumentDetailSnapshotStore store;
    private final DocumentDetailSnapshotBuilder builder;
    private final Clock clock;
    private final java.util.Set<Long> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().name("document-detail-", 0).daemon().factory());
    public DocumentDetailSnapshotWorker(DocumentDetailSnapshotRepository snapshots, DocumentDetailSnapshotStore store,
                                        DocumentDetailSnapshotBuilder builder, Clock clock) {
        this.snapshots = snapshots; this.store = store; this.builder = builder; this.clock = clock;
    }
    @Scheduled(fixedDelayString = "${app.document-detail.worker.interval-ms:3000}",
            initialDelayString = "${app.document-detail.worker.initial-delay-ms:5000}")
    public synchronized void refresh() {
        for (var id : snapshots.findMissing(PageRequest.of(0, 20))) {
            try { store.enqueueMissing(id); }
            catch (RuntimeException e) { log.warn("Detail snapshot enqueue failed. version_id={} type={}", id, e.getClass().getSimpleName()); }
        }
        int capacity = 2 - running.size();
        if (capacity <= 0) return;
        var now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        for (var id : snapshots.findDue(now, DocumentDetailSnapshotStore.POLICY, PageRequest.of(0, capacity))) {
            if (!running.add(id)) continue;
            try { executor.execute(() -> build(id)); }
            catch (RejectedExecutionException stopped) { running.remove(id); }
        }
    }
    private void build(Long id) {
        DocumentDetailSnapshotStore.Claim claim = null;
        try {
            claim = store.claim(id);
            if (claim != null) store.complete(claim, builder.build(id));
        } catch (RuntimeException error) {
            if (claim != null) store.fail(claim);
            log.warn("Detail snapshot build failed. version_id={} type={}", id, error.getClass().getSimpleName());
        } finally { running.remove(id); }
    }
    @PreDestroy void close() { executor.shutdownNow(); }
}
