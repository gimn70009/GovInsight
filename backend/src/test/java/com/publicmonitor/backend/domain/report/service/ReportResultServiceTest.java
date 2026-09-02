package com.publicmonitor.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.domain.report.entity.MonitoringReportStatus;
import com.publicmonitor.backend.domain.report.event.ReportCompletedEvent;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import com.publicmonitor.backend.domain.report.web.dto.ReportResultRequest;
import com.publicmonitor.backend.domain.report.web.dto.ReportResultResponse;
import com.publicmonitor.backend.domain.report.web.dto.ReportResultStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReportResultServiceTest {

    @Mock MonitoringRunRepository runRepository;
    @Mock MonitoringReportRepository reportRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    private ReportResultService service;
    private MonitoringRun run;
    private MonitoringReport report;

    @BeforeEach
    void setUp() {
        service = new ReportResultService(
                runRepository,
                reportRepository,
                Clock.fixed(Instant.parse("2026-08-21T01:00:00Z"), ZoneOffset.UTC),
                eventPublisher
        );
        run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, LocalDateTime.now());
        ReflectionTestUtils.setField(run, "id", 10L);
        ReflectionTestUtils.setField(run, "status", MonitoringRunStatus.COLLECTED);
        report = MonitoringReport.pending(run);
        ReflectionTestUtils.setField(report, "id", 20L);
        given(runRepository.findById(10L)).willReturn(Optional.of(run));
        given(reportRepository.findByMonitoringRunId(10L)).willReturn(Optional.of(report));
    }

    @Test
    void 보고서_완료_결과를_저장하고_실행을_완료한다() {
        ReportResultResponse response = service.receive(new ReportResultRequest(
                10L,
                UUID.fromString("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
                ReportResultStatus.COMPLETED,
                "산업통상부 사업공고 모니터링 요약",
                "신규 지원사업 공고의 대상과 접수 기한을 정리했습니다.",
                null
        ));

        assertThat(report.getStatus()).isEqualTo(MonitoringReportStatus.COMPLETED);
        assertThat(report.getTitle()).contains("산업통상부");
        assertThat(run.getStatus()).isEqualTo(MonitoringRunStatus.COMPLETED);
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(response.duplicate()).isFalse();
        verify(eventPublisher).publishEvent(any(ReportCompletedEvent.class));
    }

    @Test
    void 이미_완료된_보고서_결과는_중복으로_처리한다() {
        report.complete("기존 제목", "기존 보고서 요약 내용입니다.", LocalDateTime.now());
        ReflectionTestUtils.setField(run, "status", MonitoringRunStatus.COMPLETED);

        ReportResultResponse response = service.receive(new ReportResultRequest(
                10L,
                UUID.randomUUID(),
                ReportResultStatus.COMPLETED,
                "새 제목",
                "새 요약은 기존 결과를 덮어쓰지 않아야 합니다.",
                null
        ));

        assertThat(response.duplicate()).isTrue();
        assertThat(report.getTitle()).isEqualTo("기존 제목");
    }
}
