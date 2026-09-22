package com.publicmonitor.backend.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.publicmonitor.backend.domain.analysis.service.AnalysisJobRequestService;
import com.publicmonitor.backend.domain.analysis.service.AnalysisResultService;
import com.publicmonitor.backend.domain.document.service.CollectionResultService;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringRunService;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringSourceService;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "app.telegram.commands.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "app.jwt.secret=VGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS10aGF0LWlzLWxvbmc=",
        "app.jwt.access-token-expiration=1h",
        "app.local-admin.enabled=false",
        "app.jpa-auditing.enabled=false",
        "app.monitoring.schedule.enabled=false"
})
@AutoConfigureMockMvc
class OpenApiDocumentationIntegrationTest {

    @MockitoBean private com.publicmonitor.backend.domain.email.service.EmailSettingsService emailSettings;
    @MockitoBean private com.publicmonitor.backend.domain.email.service.EmailConnectionService emailConnection;
    @MockitoBean private com.publicmonitor.backend.domain.email.EmailReportDeliveryService emailDelivery;
    @MockitoBean private com.publicmonitor.backend.domain.report.service.ReportDeliveryQueryService reportDeliveryQuery;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.publicmonitor.backend.domain.telegram.service.TelegramSettingsService telegramSettings;
    @MockitoBean
    private com.publicmonitor.backend.domain.telegram.service.TelegramConnectionService telegramConnection;
    @MockitoBean
    private com.publicmonitor.backend.domain.telegram.service.TelegramReportQueryService telegramReports;
    @MockitoBean
    private com.publicmonitor.backend.domain.telegram.TelegramReportDeliveryService telegramDelivery;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private MonitoringSourceService monitoringSourceService;

    @MockitoBean
    private MonitoringRunService monitoringRunService;

    @MockitoBean
    private CollectionResultService collectionResultService;

    @MockitoBean
    private AnalysisJobRequestService analysisJobRequestService;

    @MockitoBean
    private AnalysisResultService analysisResultService;

    @MockitoBean
    private com.publicmonitor.backend.domain.document.service.DocumentBookmarkService bookmarkService;

    @Test
    void bookmarkEndpointsUseAuthenticatedAccountAndRejectInvalidIds() throws Exception {
        var account = com.publicmonitor.backend.domain.user.entity.User.create("bookmark-user", "test",
                com.publicmonitor.backend.domain.user.entity.Role.ADMIN);
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", 42L);
        var principal = new com.publicmonitor.backend.global.security.CustomUserDetails(account);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/bookmarks/versions/7")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal)))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(bookmarkService).set(42L, 7L, true);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/bookmarks/versions/7")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal)))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(bookmarkService).set(42L, 7L, false);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/bookmarks/versions/0")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/bookmarks/versions")).andExpect(status().isUnauthorized());
    }

    @Test
    void 공개_API_OpenAPI_문서에_현재_엔드포인트와_JWT_스키마가_포함된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs/public-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("GovInsight API"))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/monitoring-sources'].post").exists())
                .andExpect(jsonPath("$.paths['/api/monitoring-sources/{sourceId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/monitoring-sources/{sourceId}/enabled'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/monitoring-runs'].post").exists())
                .andExpect(jsonPath("$.paths['/api/monitoring-runs'].get").exists())
                .andExpect(jsonPath("$.paths['/api/document-detections'].get").exists())
                .andExpect(jsonPath("$.paths['/api/email/settings'].get").exists())
                .andExpect(jsonPath("$.paths['/api/email/test-message'].post").exists())
                .andExpect(jsonPath("$.paths['/api/report-deliveries'].get").exists())
                .andExpect(jsonPath("$.paths['/api/telegram/settings'].get").exists())
                .andExpect(jsonPath("$.paths['/api/telegram/deliveries/{deliveryId}/retry'].post").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/monitoring-sources'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").doesNotExist());
    }

    @Test
    void 내부_API_OpenAPI_문서는_Python_결과_수신_API를_포함한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs/internal-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/internal/monitoring/collection-results'].post").exists())
                .andExpect(jsonPath("$.paths['/internal/monitoring/analysis-results'].post").exists())
                .andExpect(jsonPath("$.paths['/internal/monitoring/report-results'].post").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login']").doesNotExist());
    }

    @Test
    void Swagger_UI는_인증_없이_접근할_수_있다() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }

    @Test
    void 기존_보호_API는_토큰_없이_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/api/monitoring-sources"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/document-detections"))
                .andExpect(status().isUnauthorized());
    }
}
