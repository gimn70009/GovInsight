package com.publicmonitor.backend.domain.telegram;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.telegram.commands.enabled", havingValue = "true")
public class TelegramCommandPoller {
    private static final Logger log = LoggerFactory.getLogger(TelegramCommandPoller.class);
    private final TelegramClient client;
    private final TelegramProperties properties;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private volatile boolean running;
    private Instant startedAt;
    private TelegramStartCommandHandler handler;
    private boolean listening;

    @Autowired
    public TelegramCommandPoller(TelegramClient client, TelegramProperties properties, Clock clock) {
        this(client, properties, clock, Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("telegram-commands").factory()));
    }

    TelegramCommandPoller(TelegramClient client, TelegramProperties properties, Clock clock,
                          ScheduledExecutorService executor) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (running || executor.isShutdown()) return;
        if (properties.botToken().isBlank()) {
            log.warn("Telegram /start disabled: bot token is not configured.");
            return;
        }
        if (properties.readTimeout().compareTo(Duration.ofSeconds(5)) <= 0) {
            log.warn("Telegram /start disabled: app.telegram.read-timeout must be greater than 5s.");
            return;
        }
        startedAt = clock.instant();
        running = true;
        executor.schedule(this::poll, 0, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        if (!running) return;
        long delayMillis = 250;
        try {
            if (handler == null) {
                if (!client.getWebhookInfo().url().isBlank()) {
                    log.warn("Telegram /start disabled: an existing webhook is configured. It was left unchanged.");
                    close();
                    return;
                }
                var bot = client.getMe();
                if (bot.username() == null || bot.username().isBlank()) {
                    log.warn("Telegram /start disabled: bot username is missing.");
                    close();
                    return;
                }
                handler = new TelegramStartCommandHandler(client, bot.username(), clock, startedAt);
            }
            handler.poll();
            if (!listening) {
                log.info("Telegram /start is listening for new private messages.");
                listening = true;
            }
        } catch (TelegramClientException exception) {
            Integer status = exception.statusCode();
            log.warn("Telegram /start polling failed: {}", exception.getMessage());
            if (status != null && (status == 401 || status == 404 || status == 409)) {
                close();
                return;
            }
            delayMillis = 30_000;
        } catch (RuntimeException exception) {
            // Do not print external exception messages or causes: request URLs contain the token.
            log.warn("Telegram /start polling failed unexpectedly; retrying in 30s.");
            delayMillis = 30_000;
        }
        scheduleNext(delayMillis);
    }

    private synchronized void scheduleNext(long delayMillis) {
        if (running) executor.schedule(this::poll, delayMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public synchronized void close() {
        running = false;
        executor.shutdownNow();
    }
}
