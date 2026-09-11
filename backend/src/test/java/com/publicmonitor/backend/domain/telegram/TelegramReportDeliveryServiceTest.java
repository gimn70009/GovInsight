package com.publicmonitor.backend.domain.telegram;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.domain.telegram.entity.*;
import com.publicmonitor.backend.domain.telegram.repository.TelegramDeliveryRepository;
import com.publicmonitor.backend.domain.telegram.service.*;
import com.publicmonitor.backend.domain.telegram.web.dto.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramReportDeliveryServiceTest {
    final TelegramDeliveryRepository repository = mock(TelegramDeliveryRepository.class);
    final TelegramSettingsService settings = mock(TelegramSettingsService.class);
    final TelegramClient client = mock(TelegramClient.class);
    final TelegramProperties properties = new TelegramProperties(true, "test-token", "123", Duration.ofSeconds(1), Duration.ofSeconds(1), "");
    final TelegramDeliveryWorker worker = new TelegramDeliveryWorker(repository, settings, client, properties, Clock.systemUTC());
    TelegramDelivery target;
    @BeforeEach void setup() {
        var report = MonitoringReport.pending(MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, LocalDateTime.now()));
        report.complete("보고서", "저장된 본문", LocalDateTime.now());
        target = TelegramDelivery.pending(report, new TelegramRecipient("123", "원래 이름", true));
        when(repository.findForUpdate(1L)).thenReturn(Optional.of(target));
        when(settings.effective()).thenReturn(TelegramSettings.fromDefaults(properties));
        when(settings.validateTarget(any(), eq("123"))).thenReturn(new TelegramRecipient("123", "바뀐 이름", true));
    }
    @Test void 저장된_본문과_수신자_스냅샷을_사용한다() {
        when(client.send(anyString(), anyString())).thenReturn(99L);
        worker.sendPending(1L);
        verify(client).send("123", "보고서\n\n저장된 본문");
        assertThat(target.getStatus()).isEqualTo(TelegramDeliveryState.SENT);
        assertThat(target.getRecipientName()).isEqualTo("원래 이름");
        assertThat(target.getAttemptCount()).isEqualTo(1);
    }
    @Test void 같은_자동_전송_요청을_다시_받아도_보내지_않는다() {
        worker.sendPending(1L); worker.sendPending(1L);
        verify(client, times(1)).send(anyString(), anyString());
    }
    @Test void 실패_시_개별_오류와_시도_횟수를_저장한다() {
        when(client.send(anyString(), anyString())).thenThrow(new TelegramClientException("차단됨", null));
        worker.sendPending(1L);
        assertThat(target.getErrorMessage()).isEqualTo("차단됨");
        assertThat(target.getStatus()).isEqualTo(TelegramDeliveryState.FAILED);
        worker.sendPending(1L);
        verify(client, times(1)).send(anyString(), anyString());
    }
    @Test void 실패_대상에게만_명시적으로_재전송한다() {
        target.begin(LocalDateTime.now()); target.fail("이전 실패");
        var result = worker.retry(1L, new TelegramRetryRequest("123", 1));
        assertThat(result.status()).isEqualTo(TelegramDeliveryState.SENT);
        assertThat(result.attemptCount()).isEqualTo(2);
    }
    @Test void 성공한_대상과_오래된_시도_횟수는_거절한다() {
        target.fail("실패");
        assertThatThrownBy(() -> worker.retry(1L, new TelegramRetryRequest("123", 5))).hasMessageContaining("이미 처리");
        target.complete(1L, LocalDateTime.now());
        assertThatThrownBy(() -> worker.retry(1L, new TelegramRetryRequest("123", 0))).hasMessageContaining("이미 처리");
        verifyNoInteractions(client);
    }
    @Test void 중지된_수신자에게_재전송하지_않는다() {
        target.fail("실패");
        when(settings.validateTarget(any(), eq("123"))).thenReturn(new TelegramRecipient("123", "팀", false));
        assertThatThrownBy(() -> worker.retry(1L, new TelegramRetryRequest("123", 0))).hasMessageContaining("수신 대상");
        verifyNoInteractions(client);
    }
    @Test void 전체_발송_중지와_다른_채팅_요청을_거절한다() {
        target.fail("실패");
        when(settings.effective()).thenReturn(TelegramSettings.fromDefaults(new TelegramProperties(false, "token", "123", Duration.ZERO, Duration.ZERO, "")));
        assertThatThrownBy(() -> worker.retry(1L, new TelegramRetryRequest("123", 0))).hasMessageContaining("꺼져");
        assertThatThrownBy(() -> worker.retry(1L, new TelegramRetryRequest("999", 0))).hasMessageContaining("이미 처리");
        verifyNoInteractions(client);
    }
    @Test void 한_수신자의_예외가_다른_수신자의_발송을_막지_않는다() {
        var preparation = mock(TelegramDeliveryPreparationService.class);
        var worker = mock(TelegramDeliveryWorker.class);
        when(preparation.prepare(7L)).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new IllegalStateException("DB failure")).when(worker).sendPending(2L);
        try (var executor = Executors.newFixedThreadPool(3)) {
            new TelegramReportDeliveryService(preparation, worker, executor).deliver(7L);
        }
        verify(worker).sendPending(1L); verify(worker).sendPending(3L);
        verify(worker).recordUncertainFailure(2L);
    }
}
