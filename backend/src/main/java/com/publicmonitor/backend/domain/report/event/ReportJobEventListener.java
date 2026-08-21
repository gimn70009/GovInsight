package com.publicmonitor.backend.domain.report.event;

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

    public ReportJobEventListener(@Lazy ReportJobRequestService reportJobRequestService) {
        this.reportJobRequestService = reportJobRequestService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void requestReport(AnalysisStoredEvent event) {
        try {
            reportJobRequestService.request(event.runId());
        } catch (RuntimeException exception) {
            log.error("모니터링 보고서 작업 요청에 실패했습니다. runId={}", event.runId(), exception);
        }
    }
}
