package com.publicmonitor.backend.domain.report.service;

import com.publicmonitor.backend.domain.report.client.PythonReportClient;
import com.publicmonitor.backend.domain.report.client.PythonReportClientException;
import com.publicmonitor.backend.domain.report.client.dto.PythonReportJobRequest;
import com.publicmonitor.backend.domain.report.client.dto.PythonReportJobResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Slf4j
@Lazy
@Service
@RequiredArgsConstructor
public class ReportJobRequestService {

    private final ReportPreparationService preparationService;
    private final PythonReportClient reportClient;

    public void request(Long runId) {
        Optional<PythonReportJobRequest> prepared = preparationService.prepare(runId);
        if (prepared.isEmpty()) {
            log.info("모니터링 보고서 작업 요청을 생략합니다. runId={}", runId);
            return;
        }

        try {
            PythonReportJobResponse response = reportClient.accept(prepared.get());
            log.info(
                    "모니터링 보고서 작업 접수 완료. runId={} jobId={} documentCount={}",
                    runId, response.jobId(), response.documentCount()
            );
        } catch (PythonReportClientException exception) {
            preparationService.failRequest(runId, "Python 보고서 생성 작업 접수에 실패했습니다.");
            throw exception;
        }
    }
}
