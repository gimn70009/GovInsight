package com.publicmonitor.backend.domain.analysis.service;

import com.publicmonitor.backend.domain.analysis.client.PythonAnalysisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j @Component @RequiredArgsConstructor
public class AnalysisTaskRunner {
    private final AnalysisTaskService tasks;
    private final PythonAnalysisClient client;
    public void run(Long runId) {
        var claimed = tasks.claim(runId);
        if (claimed.isEmpty()) return;
        var work = claimed.get();
        try { client.accept(work.request()); }
        catch (RuntimeException exception) {
            log.warn("분석 작업 접수 실패. runId={} type={}", runId, exception.getClass().getSimpleName());
            tasks.requestFailed(work);
        }
    }
}
