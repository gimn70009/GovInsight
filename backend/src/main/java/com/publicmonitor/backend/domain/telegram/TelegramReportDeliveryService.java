package com.publicmonitor.backend.domain.telegram;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Lazy
@Service
@RequiredArgsConstructor
public class TelegramReportDeliveryService {

    private static final int MAX_MESSAGE_LENGTH = 4_096;
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final MonitoringRunRepository runRepository;
    private final MonitoringReportRepository reportRepository;
    private final TelegramProperties properties;
    private final TelegramClient client;
    private final Clock clock;

    @Transactional
    public void deliver(Long runId) {
        MonitoringRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("모니터링 실행을 찾을 수 없습니다."));
        MonitoringReport report = reportRepository.findByMonitoringRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("모니터링 보고서를 찾을 수 없습니다."));
        if (report.getStatus() != MonitoringReportStatus.COMPLETED || report.isTelegramSent()) {
            return;
        }
        if (!properties.enabled()) {
            log.info("Telegram 보고서 전송이 비활성화되어 있습니다. runId={}", runId);
            return;
        }
        if (!properties.isConfigured()) {
            report.failTelegramDelivery("Telegram 봇 토큰 또는 채팅 ID가 설정되지 않았습니다.");
            log.error("Telegram 설정이 완전하지 않습니다. runId={}", runId);
            return;
        }

        String message = report.getTitle() + "\n\n" + report.getSummary();
        if (message.length() > MAX_MESSAGE_LENGTH) {
            report.failTelegramDelivery("Telegram 메시지 최대 길이를 초과했습니다.");
            log.error("Telegram 보고서 길이 제한을 초과했습니다. runId={} length={}", runId, message.length());
            return;
        }
        try {
            long messageId = client.send(message);
            LocalDateTime sentAt = LocalDateTime.now(clock.withZone(SERVICE_ZONE));
            report.completeTelegramDelivery(messageId, sentAt);
            log.info("Telegram 보고서 전송 완료. runId={} reportId={} messageId={}",
                    runId, report.getId(), messageId);
        } catch (TelegramClientException exception) {
            report.failTelegramDelivery(exception.getMessage());
            log.error("Telegram 보고서 전송 실패. runId={} reason={}",
                    runId, exception.getMessage());
        }
    }
}
