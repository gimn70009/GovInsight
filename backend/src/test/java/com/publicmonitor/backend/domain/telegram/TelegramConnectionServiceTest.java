package com.publicmonitor.backend.domain.telegram;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.publicmonitor.backend.domain.telegram.entity.TelegramSettings;
import com.publicmonitor.backend.domain.telegram.service.TelegramSettingsService;
import com.publicmonitor.backend.domain.telegram.service.TelegramConnectionService;
import com.publicmonitor.backend.domain.telegram.web.dto.TelegramTargetRequest;
import java.time.*;
import org.junit.jupiter.api.Test;

class TelegramConnectionServiceTest {
    private final TelegramSettingsService settings = mock(TelegramSettingsService.class);
    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramProperties properties = new TelegramProperties(false, "test-token", "123", Duration.ofSeconds(1), Duration.ofSeconds(1), "");
    private final TelegramConnectionService service = new TelegramConnectionService(settings, properties, client, Clock.systemUTC());

    @Test
    void 연결_확인은_발송하지_않으며_채팅_오류를_봇_오류와_구분한다() {
        when(settings.effective()).thenReturn(TelegramSettings.fromDefaults(properties));
        when(client.getMe()).thenReturn(new TelegramClient.BotInfo("봇", "gov_bot"));
        when(client.getChat("123")).thenThrow(new TelegramClientException("채팅 권한 없음", null));
        var result = service.check(new TelegramTargetRequest("123"));
        assertThat(result.botConnected()).isTrue();
        assertThat(result.chatConnected()).isFalse();
        assertThat(result.message()).isEqualTo("채팅 권한 없음");
        verify(client, never()).send(anyString(), anyString());
    }

    @Test
    void 발송이_꺼진_상태에서도_명시적인_테스트_메시지는_보낼_수_있다() {
        var target = TelegramSettings.fromDefaults(properties);
        when(settings.effective()).thenReturn(target);
        assertThat(service.sendTest(new TelegramTargetRequest("123")).sent()).isTrue();
        verify(settings).validateTarget(target, "123");
        verify(client).send(eq("123"), contains("연결 테스트"));
    }

    @Test
    void 테스트_메시지_실패를_성공으로_표시하지_않는다() {
        when(settings.effective()).thenReturn(TelegramSettings.fromDefaults(properties));
        when(client.send(anyString(), anyString())).thenThrow(new TelegramClientException("접근 거부", null));
        assertThat(service.sendTest(new TelegramTargetRequest("123")).sent()).isFalse();
    }
}
