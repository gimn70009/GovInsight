package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.analysis.event.*;
import com.publicmonitor.backend.domain.report.event.ProposalCompletedEvent;
import com.publicmonitor.backend.domain.document.repository.DocumentDetailSnapshotRepository;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component @RequiredArgsConstructor
@org.springframework.context.annotation.Lazy
public class DocumentDetailSnapshotEvents {
    private final DocumentDetailSnapshotRepository snapshots;
    private final Clock clock;
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void collected(CollectionStoredEvent event) { invalidate(event.runId()); }
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void analyzed(AnalysisStoredEvent event) { invalidate(event.runId()); }
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void proposed(ProposalCompletedEvent event) { invalidate(event.runId()); }
    private void invalidate(Long runId) { snapshots.invalidateRun(runId, LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")))); }
}
