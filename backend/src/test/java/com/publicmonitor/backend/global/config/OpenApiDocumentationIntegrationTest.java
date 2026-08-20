package com.publicmonitor.backend.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "app.jwt.secret=VGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS10aGF0LWlzLWxvbmc=",
        "app.jwt.access-token-expiration=1h",
        "app.local-admin.enabled=false",
        "app.jpa-auditing.enabled=false"
})
@AutoConfigureMockMvc
class OpenApiDocumentationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private MonitoringSourceService monitoringSourceService;

    @MockitoBean
    private MonitoringRunService monitoringRunService;

    @MockitoBean
    private CollectionResultService collectionResultService;

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
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/monitoring-sources'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").doesNotExist());
    }

    @Test
    void 내부_API_OpenAPI_문서는_수집_결과_API만_포함한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs/internal-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/internal/monitoring/collection-results'].post").exists())
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
    }
}
