package com.publicmonitor.backend.domain.telegram;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelegramCommandPollerTest {
    private final TelegramClient client = mock(TelegramClient.class);
    private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-09T02:00:00Z"), ZoneOffset.UTC);
    private final AtomicReference<Runnable> next = new AtomicReference<>();

    TelegramCommandPollerTest() {
        when(executor.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(invocation -> {
            next.set(invocation.getArgument(0));
            return null;
        });
        when(client.getWebhookInfo()).thenReturn(new TelegramClient.WebhookInfo(""));
        when(client.getMe()).thenReturn(new TelegramClient.BotInfo("우리 봇", "our_bot"));
        when(client.getUpdates(null)).thenReturn(List.of());
    }

    @Test
    void 보고서_발송이_꺼져있고_기본_수신자가_없어도_시작_명령을_받는다() {
        var poller = poller("test-token", 10);
        poller.start();
        poller.start();
        verify(executor, times(1)).schedule(any(Runnable.class), eq(0L), eq(TimeUnit.MILLISECONDS));
        next.get().run();
        verify(client).getUpdates(null);
        verify(executor).schedule(any(Runnable.class), eq(250L), eq(TimeUnit.MILLISECONDS));
        poller.close();
        clearInvocations(client, executor);
        next.get().run();
        verifyNoInteractions(client, executor);
    }

    @Test
    void 토큰이_없거나_응답_제한이_폴링보다_짧으면_외부_호출하지_않는다() {
        poller("", 10).start();
        poller("test-token", 5).start();
        verifyNoInteractions(client);
        verify(executor, never()).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    void 기존_웹훅이_있으면_바꾸지_않고_수신을_중지한다() {
        when(client.getWebhookInfo()).thenReturn(new TelegramClient.WebhookInfo("https://example.com/webhook"));
        var poller = poller("test-token", 10);
        poller.start();
        next.get().run();
        verify(client, never()).getMe();
        verify(client, never()).getUpdates(any());
        verify(executor).shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 404, 409})
    void 인증_실패나_다른_수신_프로그램과_충돌하면_멈춘다(int status) {
        when(client.getUpdates(null)).thenThrow(new TelegramClientException("안전한 오류 안내", status));
        var poller = poller("test-token", 10);
        poller.start();
        next.get().run();
        verify(executor).shutdownNow();
        verify(executor, times(1)).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    void 일시적_통신_실패는_간격을_두고_수신을_재개한다() {
        when(client.getUpdates(null)).thenThrow(new TelegramClientException("일시적 통신 오류", null)).thenReturn(List.of());
        var poller = poller("test-token", 10);
        poller.start();
        next.get().run();
        verify(executor).schedule(any(Runnable.class), eq(30_000L), eq(TimeUnit.MILLISECONDS));
        next.get().run();
        verify(client, times(2)).getUpdates(null);
        verify(client, times(1)).getMe();
        verify(executor).schedule(any(Runnable.class), eq(250L), eq(TimeUnit.MILLISECONDS));
    }

    private TelegramCommandPoller poller(String token, int readSeconds) {
        return new TelegramCommandPoller(client, new TelegramProperties(false, token, "", Duration.ofSeconds(3),
                Duration.ofSeconds(readSeconds), ""), clock, executor);
    }
}
