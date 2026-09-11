package com.publicmonitor.backend.domain.telegram.service;

import com.publicmonitor.backend.domain.telegram.TelegramClient;
import com.publicmonitor.backend.domain.telegram.TelegramClientException;
import com.publicmonitor.backend.domain.telegram.TelegramProperties;
import com.publicmonitor.backend.domain.telegram.web.dto.TelegramConnectionResponse;
import com.publicmonitor.backend.domain.telegram.web.dto.TelegramTargetRequest;
import com.publicmonitor.backend.domain.telegram.web.dto.TelegramTestResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Lazy
@Service
@RequiredArgsConstructor
public class TelegramConnectionService {
    private final TelegramSettingsService settingsService;
    private final TelegramProperties properties;
    private final TelegramClient client;
    private final Clock clock;

    public TelegramConnectionResponse check(TelegramTargetRequest request) {
        var settings = settingsService.effective();
        settingsService.validateTarget(settings, request.expectedChatId());
        var checkedAt = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        if (properties.botToken().isBlank()) {
            return new TelegramConnectionResponse(false, false, null, null, null, null,
                    "서버에 Telegram 봇 토큰이 설정되지 않았습니다.", checkedAt);
        }
        TelegramClient.BotInfo bot;
        try {
            bot = client.getMe();
        } catch (TelegramClientException exception) {
            return new TelegramConnectionResponse(false, false, null, null, null, null,
                    exception.getMessage(), checkedAt);
        }
        try {
            var chat = client.getChat(request.expectedChatId());
            return new TelegramConnectionResponse(true, true, bot.firstName(), bot.username(),
                    chat.displayName(), chat.type(), "봇과 채팅을 확인했습니다. 실제 발송은 테스트 메시지로 확인할 수 있어요.", checkedAt);
        } catch (TelegramClientException exception) {
            return new TelegramConnectionResponse(true, false, bot.firstName(), bot.username(), null, null,
                    exception.getMessage(), checkedAt);
        }
    }

    public TelegramTestResponse sendTest(TelegramTargetRequest request) {
        var settings = settingsService.effective();
        settingsService.validateTarget(settings, request.expectedChatId());
        try {
            client.send(request.expectedChatId(), "[GovInsight] Telegram 연결 테스트\n이 채팅으로 보고서를 받을 수 있습니다.");
            return new TelegramTestResponse(true, "테스트 메시지를 보냈습니다. Telegram 채팅을 확인해 주세요.");
        } catch (TelegramClientException exception) {
            return new TelegramTestResponse(false, exception.getMessage());
        }
    }
}
