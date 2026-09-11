package com.publicmonitor.backend.domain.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One handler belongs to one running polling session; it never registers report recipients. */
final class TelegramStartCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(TelegramStartCommandHandler.class);
    private final TelegramClient client;
    private final String botUsername;
    private final Clock clock;
    private final long startedAtSecond;
    private final Map<Long, Instant> recentAttempts = new HashMap<>();
    private Long offset;
    private Instant lastUpdateAt;

    TelegramStartCommandHandler(TelegramClient client, String botUsername, Clock clock, Instant startedAt) {
        this.client = client;
        this.botUsername = botUsername;
        this.clock = clock;
        this.startedAtSecond = startedAt.getEpochSecond();
        this.lastUpdateAt = startedAt;
    }

    void poll() {
        Instant now = clock.instant();
        // Telegram retains updates for 24h and may reset IDs after a week without messages.
        if (lastUpdateAt.plus(Duration.ofDays(2)).isBefore(now)) offset = null;
        recentAttempts.values().removeIf(time -> !time.plusSeconds(2).isAfter(now));
        for (var update : client.getUpdates(offset)) {
            if (Thread.currentThread().isInterrupted()) return;
            if (update == null || (offset != null && update.updateId() < offset)) continue;
            // Advance even if send times out: retrying an uncertain send can produce duplicate replies.
            offset = update.updateId() + 1;
            lastUpdateAt = clock.instant();
            var message = update.message();
            if (!isStart(message)) continue;
            long chatId = message.chat().id();
            Instant attemptedAt = recentAttempts.get(chatId);
            if (attemptedAt != null && attemptedAt.plusSeconds(2).isAfter(clock.instant())) continue;
            recentAttempts.put(chatId, clock.instant());
            try {
                client.send(Long.toString(chatId), "GovInsight 봇에 연결됐습니다.\n\n내 채팅 ID: " + chatId
                        + "\n\n이 숫자를 보고서 담당자에게 전달해 주세요. 수신자로 등록되면 보고서를 받을 수 있어요.");
                log.info("Telegram /start replied: updateId={}", update.updateId());
            } catch (TelegramClientException exception) {
                log.warn("Telegram /start reply failed: updateId={}, reason={}", update.updateId(), exception.getMessage());
            }
        }
    }

    private boolean isStart(TelegramClient.IncomingMessage message) {
        // Skip pre-start messages (including the startup second) instead of surprising old users.
        if (message == null || message.date() <= startedAtSecond || message.chat() == null
                || !"private".equals(message.chat().type()) || message.chat().id() <= 0
                || message.from() == null || message.from().bot() || message.from().id() != message.chat().id()
                || message.text() == null || message.entities() == null) return false;
        String text = message.text();
        return message.entities().stream().anyMatch(entity -> {
            if (entity == null || !"bot_command".equals(entity.type()) || entity.offset() != 0
                    || entity.length() <= 0 || entity.length() > text.length()) return false;
            String command = text.substring(0, entity.length());
            boolean matches = command.equals("/start") || command.equalsIgnoreCase("/start@" + botUsername);
            return matches && (entity.length() == text.length() || Character.isWhitespace(text.charAt(entity.length())));
        });
    }
}
