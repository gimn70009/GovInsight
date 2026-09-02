package com.publicmonitor.backend.domain.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TelegramReportDeliveryServiceTest {

    @Mock MonitoringRunRepository runRepository;
    @Mock MonitoringReportRepository reportRepository;
    @Mock TelegramClient client;

    private TelegramReportDeliveryService service;
    private MonitoringRun run;
    private MonitoringReport report;

    @BeforeEach
    void setUp() {
        TelegramProperties properties = new TelegramProperties(
                true, "test-token", "123456", Duration.ofSeconds(1), Duration.ofSeconds(1)
        );
        service = new TelegramReportDeliveryService(
                runRepository,
                reportRepository,
                properties,
                client,
                Clock.fixed(Instant.parse("2026-09-02T00:30:00Z"), ZoneOffset.UTC)
        );
        run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, LocalDateTime.now());
        ReflectionTestUtils.setField(run, "id", 10L);
        report = MonitoringReport.pending(run);
        ReflectionTestUtils.setField(report, "id", 20L);
        report.complete(
                "[공공기관 모니터링] 9월 2일 보고서",
                "신규 1건 │ 수정 0건 │ 변경 없음 0건\n\n🔴 신규 사업공고",
                LocalDateTime.now()
        );
        given(runRepository.findById(10L)).willReturn(Optional.of(run));
    }

    @Test
    void 수동_실행의_최종_보고서를_한_메시지로_전송한다() {
        given(reportRepository.findByMonitoringRunId(10L)).willReturn(Optional.of(report));
        given(client.send(org.mockito.ArgumentMatchers.anyString())).willReturn(777L);

        service.deliver(10L);

        verify(client).send(org.mockito.ArgumentMatchers.contains("[공공기관 모니터링]"));
        assertThat(report.getTelegramMessageId()).isEqualTo(777L);
        assertThat(report.getTelegramSentAt()).isNotNull();
        assertThat(report.getTelegramErrorMessage()).isNull();
    }

    @Test
    void 이미_전송한_보고서는_중복_전송하지_않는다() {
        report.completeTelegramDelivery(777L, LocalDateTime.now());
        given(reportRepository.findByMonitoringRunId(10L)).willReturn(Optional.of(report));

        service.deliver(10L);

        verify(client, never()).send(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 자동_실행은_Telegram으로_전송하지_않는다() {
        MonitoringRun scheduled = MonitoringRun.create(
                MonitoringTriggerType.SCHEDULED, 1, LocalDateTime.now()
        );
        ReflectionTestUtils.setField(scheduled, "id", 10L);
        given(runRepository.findById(10L)).willReturn(Optional.of(scheduled));

        service.deliver(10L);

        verify(client, never()).send(org.mockito.ArgumentMatchers.anyString());
    }
}