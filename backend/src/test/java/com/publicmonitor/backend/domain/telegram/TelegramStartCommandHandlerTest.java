package com.publicmonitor.backend.domain.telegram;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramStartCommandHandlerTest {
    private static final Instant START = Instant.parse("2026-09-09T02:00:00Z");
    private final TelegramClient client = mock(TelegramClient.class);
    private final Clock clock = mock(Clock.class);
    private final TelegramStartCommandHandler handler = new TelegramStartCommandHandler(client, "our_bot", clock, START);

    TelegramStartCommandHandlerTest() {
        when(clock.instant()).thenReturn(START.plusSeconds(1));
    }

    @Test
    void 새_개인_시작_명령에_본인_숫자_ID와_등록_안내를_답장한다() {
        long chatId = 9_123_456_789L;
        when(client.getUpdates(null)).thenReturn(List.of(update(10, chatId, "/start")));
        handler.poll();
        verify(client).send(eq(Long.toString(chatId)), argThat(text -> text.contains("내 채팅 ID: " + chatId)
                && text.contains("담당자에게 전달") && text.contains("수신자로 등록되면")));
        verify(client).getUpdates(null);
        verifyNoMoreInteractions(client);
    }

    @Test
    void 이전_명령은_무시하고_새_명령만_처리한_다음_offset을_전달한다() {
        var old = new TelegramClient.Update(1, message(100, "private", "/start", START.getEpochSecond()));
        when(client.getUpdates(null)).thenReturn(List.of(old, update(2, 200, "/start")));
        when(client.getUpdates(3L)).thenReturn(List.of());
        handler.poll();
        handler.poll();
        verify(client, never()).send(eq("100"), anyString());
        verify(client).send(eq("200"), anyString());
        verify(client).getUpdates(3L);
    }

    @Test
    void 같은_업데이트와_빠른_반복_시작에는_중복_답장하지_않는다() {
        var first = update(10, 100, "/start");
        when(client.getUpdates(null)).thenReturn(List.of(first, first, update(11, 100, "/start")));
        when(client.getUpdates(12L)).thenReturn(List.of(first, update(12, 100, "/start")));
        handler.poll();
        handler.poll();
        verify(client, times(1)).send(eq("100"), anyString());
    }

    @Test
    void 다시_보낸_시작과_우리_봇을_지정한_시작_및_시작_인자를_받는다() {
        when(client.getUpdates(null)).thenReturn(List.of(update(1, 100, "/start@OUR_BOT invite")));
        handler.poll();
        when(clock.instant()).thenReturn(START.plusSeconds(5));
        when(client.getUpdates(2L)).thenReturn(List.of(update(2, 100, "/start any-payload")));
        handler.poll();
        verify(client, times(2)).send(eq("100"), anyString());
    }

    @Test
    void 그룹_일반문자_다른명령_다른봇_편집_잘못된_명령범위는_무시한다() {
        var group = new TelegramClient.Update(1, message(-100, "supergroup", "/start", START.getEpochSecond() + 1));
        var invalidEntity = new TelegramClient.Update(8, new TelegramClient.IncomingMessage(
                START.getEpochSecond() + 1, chat(100, "private"), new TelegramClient.Sender(100, false), "/start",
                List.of(new TelegramClient.MessageEntity("bot_command", 0, 99))));
        var impersonation = new TelegramClient.Update(9, new TelegramClient.IncomingMessage(
                START.getEpochSecond() + 1, chat(100, "private"), new TelegramClient.Sender(200, false), "/start",
                List.of(new TelegramClient.MessageEntity("bot_command", 0, 6))));
        when(client.getUpdates(null)).thenReturn(List.of(group, update(2, 100, "안녕하세요"),
                update(3, 100, "/starter"), update(4, 100, "/start@other_bot"), update(5, 100, "/help"),
                new TelegramClient.Update(6, null), update(7, 100, "hello /start"), invalidEntity, impersonation));
        handler.poll();
        verify(client, never()).send(anyString(), anyString());
    }

    @Test
    void 봇이_보낸_메시지와_명령_엔티티_없는_문자에는_답장하지_않는다() {
        var bot = new TelegramClient.Update(1, new TelegramClient.IncomingMessage(
                START.getEpochSecond() + 1, chat(100, "private"), new TelegramClient.Sender(100, true), "/start",
                List.of(new TelegramClient.MessageEntity("bot_command", 0, 6))));
        var plain = new TelegramClient.Update(2, new TelegramClient.IncomingMessage(
                START.getEpochSecond() + 1, chat(100, "private"), new TelegramClient.Sender(100, false), "/start", null));
        when(client.getUpdates(null)).thenReturn(List.of(bot, plain));
        handler.poll();
        verify(client, never()).send(anyString(), anyString());
    }

    @Test
    void 답장_시간초과를_자동_재발송하지_않고_다른_사람은_계속_처리한다() {
        var first = update(1, 100, "/start");
        when(client.getUpdates(null)).thenReturn(List.of(first, update(2, 200, "/start")));
        when(client.send(eq("100"), anyString())).thenThrow(new TelegramClientException("응답을 확인하지 못했습니다.", null));
        handler.poll();
        when(client.getUpdates(3L)).thenReturn(List.of(first));
        handler.poll();
        verify(client, times(1)).send(eq("100"), anyString());
        verify(client).send(eq("200"), anyString());
    }

    @Test
    void 오래_사용하지_않아_업데이트_ID가_작아져도_다시_받는다() {
        when(client.getUpdates(null)).thenReturn(List.of(update(900, 100, "/start"))).thenReturn(List.of(update(10, 200, "/start")));
        handler.poll();
        when(clock.instant()).thenReturn(START.plusSeconds(8 * 86400));
        handler.poll();
        verify(client, times(2)).getUpdates(null);
        verify(client).send(eq("200"), anyString());
    }

    private TelegramClient.Update update(long id, long chatId, String text) {
        return new TelegramClient.Update(id, message(chatId, "private", text, START.getEpochSecond() + 1));
    }

    private TelegramClient.IncomingMessage message(long chatId, String type, String text, long date) {
        String command = text.split("\\s+", 2)[0];
        return new TelegramClient.IncomingMessage(date, chat(chatId, type), new TelegramClient.Sender(chatId, false), text,
                List.of(new TelegramClient.MessageEntity("bot_command", 0, command.length())));
    }

    private TelegramClient.ChatInfo chat(long id, String type) {
        return new TelegramClient.ChatInfo(id, type, null, null, null);
    }
}
