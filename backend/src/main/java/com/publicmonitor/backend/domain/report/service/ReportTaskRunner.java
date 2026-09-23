package com.publicmonitor.backend.domain.report.service;

import org.springframework.context.annotation.Lazy;
import com.publicmonitor.backend.domain.email.EmailReportDeliveryService;
import com.publicmonitor.backend.domain.telegram.TelegramReportDeliveryService;
import com.publicmonitor.backend.domain.report.client.PythonReportClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j @Lazy @Service @RequiredArgsConstructor
public class ReportTaskRunner {
    private final ReportTaskService tasks;
    private final PythonReportClient client;
    private final EmailReportDeliveryService email;
    private final TelegramReportDeliveryService telegram;
    private final ReportDeliveryAttemptService attempts;

    public void run(Long runId) {
        var claimed = tasks.claim(runId);
        if (claimed.isEmpty()) return;
        var work = claimed.get();
        if (!work.delivery()) {
            try { client.accept(work.request()); }
            catch (RuntimeException e) {
                log.warn("보고서 보강 요청 실패. runId={} type={}", runId, e.getClass().getSimpleName());
                tasks.requestFailed(work);
            }
            return;
        }
        boolean success = true;
        try { telegram.deliver(runId); }
        catch (RuntimeException e) { success = false; log.warn("Telegram 발송 작업 복구 대기. runId={}", runId); }
        try { email.deliver(runId); }
        catch (RuntimeException e) { success = false; log.warn("이메일 발송 작업 복구 대기. runId={}", runId); }
        tasks.deliveryFinished(work, success && !attempts.hasPending(runId));
    }
}
