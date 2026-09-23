package com.publicmonitor.backend.domain.telegram;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.publicmonitor.backend.domain.analysis.service.AnalysisJobRequestService;
import com.publicmonitor.backend.domain.analysis.service.AnalysisResultService;
import com.publicmonitor.backend.domain.document.service.CollectionResultService;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringRunService;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringSourceService;
import com.publicmonitor.backend.domain.telegram.service.*;
import com.publicmonitor.backend.domain.telegram.web.dto.*;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.report.recovery.enabled=false",
    "app.telegram.commands.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    "app.jwt.secret=VGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS10aGF0LWlzLWxvbmc=",
    "app.local-admin.enabled=false", "app.jpa-auditing.enabled=false", "app.monitoring.schedule.enabled=false"
})
@AutoConfigureMockMvc
class TelegramControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean TelegramSettingsService settings;
    @MockitoBean TelegramConnectionService connection;
    @MockitoBean TelegramReportQueryService reports;
    @MockitoBean TelegramReportDeliveryService delivery;
    @MockitoBean CollectionResultService collectionResultService;
    @MockitoBean AnalysisJobRequestService analysisJobRequestService;
    @MockitoBean AnalysisResultService analysisResultService;
    @MockitoBean MonitoringSourceService monitoringSourceService;
    @MockitoBean MonitoringRunService monitoringRunService;
    @MockitoBean UserRepository userRepository;

    @Test
    void 인증_없이는_설정을_조회할_수_없다() throws Exception {
        mvc.perform(get("/api/telegram/settings")).andExpect(status().isUnauthorized());
        verifyNoInteractions(settings);
    }

    @Test
    @WithMockUser(roles = "USER")
    void 일반_사용자는_수신_설정과_보고서_발송에_접근할_수_없다() throws Exception {
        mvc.perform(get("/api/telegram/settings")).andExpect(status().isForbidden());
        mvc.perform(get("/api/telegram/reports")).andExpect(status().isForbidden());
        mvc.perform(post("/api/telegram/test-message").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedChatId\":\"123\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(settings, reports, connection);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 설정_응답에는_봇_토큰을_포함하지_않는다() throws Exception {
        when(settings.find()).thenReturn(new TelegramSettingsResponse(null, true, true, java.util.List.of(new TelegramRecipientRequest("123", "팀", true)), null));
        mvc.perform(get("/api/telegram/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipients[0].chatId").value("123"))
                .andExpect(jsonPath("$.data.botConfigured").value(true))
                .andExpect(jsonPath("$.data.botToken").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 설정과_재전송의_잘못된_입력을_거절한다() throws Exception {
        for (String body : java.util.List.of("{}", "{\"enabled\":true,\"chatId\":\"https://example.org\",\"recipientName\":\"팀\"}")) {
            mvc.perform(put("/api/telegram/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/telegram/deliveries/1/retry").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedChatId\":\"123\",\"expectedAttemptCount\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/telegram/reports?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/telegram/reports?status=INVALID")).andExpect(status().isBadRequest());
        verifyNoInteractions(settings, delivery, reports);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관리자가_설정하고_명시적으로_테스트_발송할_수_있다() throws Exception {
        when(settings.update(any())).thenReturn(new TelegramSettingsResponse(0L, false, true, java.util.List.of(new TelegramRecipientRequest("-100123", "팀", true)), null));
        mvc.perform(put("/api/telegram/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":null,\"enabled\":false,\"recipients\":[{\"chatId\":\"-100123\",\"name\":\"팀\",\"enabled\":true}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        when(connection.sendTest(any())).thenReturn(new TelegramTestResponse(true, "테스트 발송 완료"));
        mvc.perform(post("/api/telegram/test-message").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedChatId\":\"-100123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sent").value(true));
        verify(connection).sendTest(new TelegramTargetRequest("-100123"));
    }
}
