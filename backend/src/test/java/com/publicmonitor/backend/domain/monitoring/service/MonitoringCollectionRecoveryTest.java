package com.publicmonitor.backend.domain.monitoring.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import java.time.*;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonitoringCollectionRecoveryTest {
    @Mock MonitoringRunRepository runs;
    @Mock MonitoringCollectionRecoveryService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void 한국시간_기준_설정한_시간이_지난_수집만_복구하며_개별_실패는_격리한다() {
        var now = LocalDateTime.of(2026, 10, 2, 12, 0);
        var cutoff = now.minusMinutes(30);
        given(runs.findExpiredCollections(eq(EnumSet.of(MonitoringRunStatus.REQUESTED,
                MonitoringRunStatus.ACCEPTED, MonitoringRunStatus.RUNNING)), eq(cutoff), any()))
                .willReturn(List.of(1L, 2L));
        given(service.expire(1L, cutoff, now)).willThrow(new IllegalStateException("DB unavailable"));
        new MonitoringCollectionRecovery(runs, service, clock, Duration.ofMinutes(30), true).recover();
        verify(service).expire(2L, cutoff, now);
    }

    @Test
    void 복구를_끄면_수집_상태를_조회하거나_변경하지_않는다() {
        new MonitoringCollectionRecovery(runs, service, clock, Duration.ofHours(1), false).recover();
        verifyNoInteractions(runs, service);
    }

    @Test
    void 잘못된_대기_시간으로_정상_실행을_즉시_실패_처리하지_않는다() {
        for (var timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> new MonitoringCollectionRecovery(runs, service, clock, timeout, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
