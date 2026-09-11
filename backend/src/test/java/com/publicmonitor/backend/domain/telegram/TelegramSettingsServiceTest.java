package com.publicmonitor.backend.domain.telegram;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.publicmonitor.backend.domain.telegram.entity.*;
import com.publicmonitor.backend.domain.telegram.exception.TelegramException;
import com.publicmonitor.backend.domain.telegram.repository.TelegramSettingsRepository;
import com.publicmonitor.backend.domain.telegram.service.TelegramSettingsService;
import com.publicmonitor.backend.domain.telegram.web.dto.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TelegramSettingsServiceTest {
    final TelegramSettingsRepository repository = mock(TelegramSettingsRepository.class);
    final TelegramProperties properties = new TelegramProperties(true, "test-token", "123", Duration.ofSeconds(1), Duration.ofSeconds(1), "");
    final TelegramSettingsService service = new TelegramSettingsService(repository, properties);
    TelegramRecipientRequest recipient(String id, boolean enabled) { return new TelegramRecipientRequest(id, "팀", enabled); }
    @Test void 최초_조회는_기존_수신자를_보존하고_DB를_변경하지_않는다() {
        var result = service.find();
        assertThat(result.enabled()).isTrue();
        assertThat(result.recipients()).extracting(TelegramRecipientRequest::chatId).containsExactly("123");
        assertThat(result.version()).isNull();
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void 여러_수신자의_순서와_개별_수신_설정을_저장한다() {
        var result = service.update(new UpdateTelegramSettingsRequest(null, true,
                List.of(recipient("123", true), recipient("-456", false), recipient("@MyTeam", true))));
        assertThat(result.recipients()).extracting(TelegramRecipientRequest::chatId).containsExactly("123", "-456", "@myteam");
        assertThat(result.recipients().get(1).enabled()).isFalse();
    }
    @Test void 채널명_대소문자가_다른_중복도_거절한다() {
        assertThatThrownBy(() -> service.update(new UpdateTelegramSettingsRequest(null, true,
                List.of(recipient("@MyTeam", true), recipient("@myteam", true)))))
                .isInstanceOf(TelegramException.class).hasMessageContaining("한 번만");
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void 모든_수신자가_중지되어도_전체_발송_설정은_독립적으로_유지한다() {
        var stopped = service.update(new UpdateTelegramSettingsRequest(null, true, List.of(recipient("123", false))));
        assertThat(stopped.enabled()).isTrue();
        assertThat(stopped.recipients().getFirst().enabled()).isFalse();
        assertThat(service.update(new UpdateTelegramSettingsRequest(null, true, List.of())).recipients()).isEmpty();
    }
    @Test void 기본_이름은_서버_설정에서_가져오고_저장된_이름은_덮어쓰지_않는다() {
        var namedProperties = new TelegramProperties(true, "test-token", "123", Duration.ofSeconds(1), Duration.ofSeconds(1), "기본 사용자");
        var namedService = new TelegramSettingsService(repository, namedProperties);
        assertThat(namedService.find().recipients().getFirst().name()).isEqualTo("기본 사용자");
        var saved = TelegramSettings.fromDefaults(namedProperties);
        saved.update(true, List.of(new TelegramRecipient("123", "직접 정한 이름", true)));
        when(repository.findById(1L)).thenReturn(Optional.of(saved));
        assertThat(namedService.find().recipients().getFirst().name()).isEqualTo("직접 정한 이름");
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void 오래된_화면은_수신자_목록을_덮어쓸_수_없다() {
        var saved = TelegramSettings.fromDefaults(properties);
        ReflectionTestUtils.setField(saved, "version", 2L);
        when(repository.findById(1L)).thenReturn(Optional.of(saved));
        assertThatThrownBy(() -> service.update(new UpdateTelegramSettingsRequest(1L, false, List.of())))
                .hasMessageContaining("설정이 변경");
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void 삭제된_대상은_발송할_수_없고_등록된_중지_대상의_명시적_테스트는_허용한다() {
        var saved = TelegramSettings.fromDefaults(properties);
        saved.update(false, List.of(new TelegramRecipient("456", null, false)));
        assertThatThrownBy(() -> service.validateTarget(saved, "123")).hasMessageContaining("수신 대상");
        assertThat(service.validateTarget(saved, "456").getName()).isEmpty();
    }
    @Test void 봇_토큰이_없으면_발송을_켤_수_없다() {
        var service = new TelegramSettingsService(repository, new TelegramProperties(false, "", "", Duration.ofSeconds(1), Duration.ofSeconds(1), ""));
        assertThatThrownBy(() -> service.update(new UpdateTelegramSettingsRequest(null, true, List.of(recipient("123", true)))))
                .isInstanceOf(TelegramException.class);
    }
}
