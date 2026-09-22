package com.publicmonitor.backend.domain.email.service;
import com.publicmonitor.backend.domain.email.EmailClient;
import com.publicmonitor.backend.domain.email.EmailClientException;
import com.publicmonitor.backend.domain.email.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
@Lazy @Service @RequiredArgsConstructor
public class EmailConnectionService {
    private final EmailSettingsService settings;
    private final EmailClient client;
    public EmailTestResponse sendTest(EmailTargetRequest request) {
        var target = settings.validateTarget(settings.effective(), request.expectedAddress());
        try {
            client.send(target.getAddress(), "[GovInsight] 이메일 연결 테스트", "이 주소로 모니터링 보고서를 받을 수 있습니다.\nGovInsight에서 이메일 발송을 켜 주세요.");
            return new EmailTestResponse(true, "메일 서버에 전달했습니다. 수신함 또는 스팸함을 확인해 주세요.");
        } catch (EmailClientException exception) { return new EmailTestResponse(false, exception.getMessage()); }
    }
}
