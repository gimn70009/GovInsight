package com.publicmonitor.backend.domain.analysis.event;

import com.publicmonitor.backend.domain.analysis.client.PythonAnalysisClient;
import com.publicmonitor.backend.domain.analysis.client.PythonAnalysisClientException;
import com.publicmonitor.backend.domain.analysis.client.dto.PythonAnalysisJobRequest;
import com.publicmonitor.backend.domain.analysis.service.AnalysisJobRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisJobEventListener {

    private final AnalysisJobRequestService requestService;
    private final PythonAnalysisClient pythonAnalysisClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void requestAnalysis(CollectionStoredEvent event) {
        try {
            requestService.prepare(event.runId()).ifPresentOrElse(
                    this::send,
                    () -> log.info("AI 분석 대상 문서가 없습니다. runId={}", event.runId())
            );
        } catch (PythonAnalysisClientException exception) {
            log.error("Python AI 분석 작업 접수에 실패했습니다. runId={}", event.runId(), exception);
        } catch (RuntimeException exception) {
            log.error("AI 분석 작업 요청 준비에 실패했습니다. runId={}", event.runId(), exception);
        }
    }

    private void send(PythonAnalysisJobRequest request) {
        var response = pythonAnalysisClient.accept(request);
        log.info(
                "Python AI 분석 작업 접수가 완료됐습니다. runId={} jobId={} documentCount={}",
                request.runId(),
                response.jobId(),
                response.documentCount()
        );
    }
}
