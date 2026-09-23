package com.publicmonitor.backend.domain.email;
import com.publicmonitor.backend.domain.telegram.TelegramReportDeliveryService;
import com.publicmonitor.backend.domain.email.service.*;
import com.publicmonitor.backend.domain.email.web.dto.*;
import com.publicmonitor.backend.domain.report.service.ReportDeliveryQueryService;

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
class EmailControllerIntegrationTest {
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


    @MockitoBean EmailSettingsService emailSettings;
    @MockitoBean EmailConnectionService emailConnection;
    @MockitoBean EmailReportDeliveryService emailDelivery;
    @MockitoBean ReportDeliveryQueryService history;
    @Test void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/email/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/report-deliveries")).andExpect(status().isUnauthorized());
        verifyNoInteractions(emailSettings, history);
    }
    @Test @WithMockUser(roles = "USER") void onlyAdministratorsCanManageDelivery() throws Exception {
        mvc.perform(get("/api/email/settings")).andExpect(status().isForbidden());
        mvc.perform(get("/api/report-deliveries")).andExpect(status().isForbidden());
        mvc.perform(post("/api/email/test-message").contentType(MediaType.APPLICATION_JSON).content("{\"expectedAddress\":\"a@gmail.com\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(emailSettings, history, emailConnection);
    }
    @Test @WithMockUser(roles = "ADMIN") void settingsExposeOnlySenderMetadataAndPersistRecipients() throws Exception {
        var response = new EmailSettingsResponse(0L, false, true, "GMAIL", "sender@gmail.com", "GovInsight", java.util.List.of(new EmailRecipientRequest("a@naver.com", "담당자", true)), null);
        when(emailSettings.find()).thenReturn(response); when(emailSettings.update(any())).thenReturn(response);
        mvc.perform(get("/api/email/settings")).andExpect(status().isOk()).andExpect(jsonPath("$.data.password").doesNotExist()).andExpect(jsonPath("$.data.provider").value("GMAIL"));
        mvc.perform(put("/api/email/settings").contentType(MediaType.APPLICATION_JSON).content("{\"version\":null,\"enabled\":false,\"recipients\":[{\"address\":\"a@naver.com\",\"name\":\"담당자\",\"enabled\":true}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.recipients[0].address").value("a@naver.com"));
        when(emailConnection.sendTest(any())).thenReturn(new EmailTestResponse(true, "테스트 발송 완료"));
        mvc.perform(post("/api/email/test-message").contentType(MediaType.APPLICATION_JSON).content("{\"expectedAddress\":\"a@naver.com\"}")).andExpect(status().isOk());
        verify(emailConnection).sendTest(new EmailTargetRequest("a@naver.com"));
    }
    @Test @WithMockUser(roles = "ADMIN") void invalidAddressesRetryVersionsAndFiltersAreRejected() throws Exception {
        for (String address : java.util.List.of("not-an-email", "a@gmail.com,b@naver.com", "<a@gmail.com>")) {
            mvc.perform(put("/api/email/settings").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"recipients\":[{\"address\":\""+address+"\",\"name\":\"\",\"enabled\":true}]}"))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/email/deliveries/1/retry").contentType(MediaType.APPLICATION_JSON).content("{\"expectedAddress\":\"a@gmail.com\",\"expectedAttemptCount\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/report-deliveries?channel=INVALID")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/report-deliveries?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/report-deliveries?size=101")).andExpect(status().isBadRequest());
        verifyNoInteractions(emailSettings, emailDelivery, history);
    }
}
