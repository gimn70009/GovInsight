package com.publicmonitor.backend.domain.analysis.event;

import com.publicmonitor.backend.domain.analysis.service.AnalysisTaskRunner;
import com.publicmonitor.backend.domain.analysis.service.AnalysisTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j @Component @RequiredArgsConstructor
public class AnalysisJobEventListener {
    private final AnalysisTaskService tasks;
    private final AnalysisTaskRunner runner;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void saveTask(CollectionStoredEvent event) { tasks.enqueue(event.runId()); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void requestAnalysis(CollectionStoredEvent event) {
        try { runner.run(event.runId()); }
        catch (RuntimeException exception) {
            log.warn("분석 작업 시작 실패, DB 작업에서 복구합니다. runId={} type={}",
                    event.runId(), exception.getClass().getSimpleName());
        }
    }
}
