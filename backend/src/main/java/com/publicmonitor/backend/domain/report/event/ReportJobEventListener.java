package com.publicmonitor.backend.domain.report.event;

import com.publicmonitor.backend.domain.analysis.event.CollectionStoredEvent;
import com.publicmonitor.backend.domain.analysis.service.AnalysisJobRequestService;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.service.ReportPreparationService;
import com.publicmonitor.backend.domain.report.service.ReportJobRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class ReportJobEventListener {

    private final ReportJobRequestService reportJobRequestService;
    private final ReportPreparationService preparation;
    private final AnalysisJobRequestService analysis;
    private final MonitoringRunRepository runs;

    public ReportJobEventListener(@Lazy ReportJobRequestService reportJobRequestService, @Lazy ReportPreparationService preparation,
            @Lazy AnalysisJobRequestService analysis,
            @Lazy MonitoringRunRepository runs) {
        this.reportJobRequestService = reportJobRequestService;
        this.preparation = preparation;
        this.analysis = analysis; this.runs = runs;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void saveReusedReportTask(CollectionStoredEvent event) {
        var run = runs.findById(event.runId()).orElseThrow();
        if (run.getStatus() == MonitoringRunStatus.COLLECTED
                && analysis.prepare(event.runId()).isEmpty()) preparation.enqueue(event.runId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void saveReportTask(ProposalCompletedEvent event) {
        preparation.enqueue(event.runId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void requestReport(ProposalCompletedEvent event) {
        try {
            reportJobRequestService.request(event.runId());
        } catch (RuntimeException exception) {
            log.error("모니터링 보고서 작업 요청에 실패했습니다. runId={}", event.runId(), exception);
        }
    }
}
