package com.publicmonitor.backend.domain.monitoring.web.controller;

import com.publicmonitor.backend.domain.analysis.service.AnalysisJobRequestService;
import com.publicmonitor.backend.domain.document.service.CollectionResultService;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.publicmonitor.backend.domain.monitoring.exception.DuplicateMonitoringSourceException;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringSourceNotFoundException;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringSourceService;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringRunService;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringSourceRequest;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringSourceResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.UpdateMonitoringSourceEnabledRequest;
import com.publicmonitor.backend.domain.monitoring.web.dto.UpdateMonitoringSourceRequest;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
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
@WithMockUser(roles = "ADMIN")
class MonitoringSourceControllerIntegrationTest {

    @MockitoBean
    private CollectionResultService collectionResultService;

    @MockitoBean
    private AnalysisJobRequestService analysisJobRequestService;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitoringSourceService monitoringSourceService;

    @MockitoBean
    private MonitoringRunService monitoringRunService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void 모니터링_소스를_등록하면_201을_반환한다() throws Exception {
        MonitoringSourceResponse response = response(1L, false);
        given(monitoringSourceService.create(org.mockito.ArgumentMatchers.any(CreateMonitoringSourceRequest.class)))
                .willReturn(response);

        mockMvc.perform(post("/api/monitoring-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "조달청",
                                  "boardName": "공지사항",
                                  "description": "조달청 공지 모니터링",
                                  "listUrl": "https://example.go.kr/notices",
                                  "urlIncludePattern": "/notice/view",
                                  "detailFetchCount": 3,
                                  "enabled": false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS_201"))
                .andExpect(jsonPath("$.data.sourceId").value(1))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void 필수_등록값이_비어있으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/monitoring-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL_400_2"))
                .andExpect(jsonPath("$.data.organizationName").exists())
                .andExpect(jsonPath("$.data.boardName").exists())
                .andExpect(jsonPath("$.data.listUrl").exists());

        verifyNoInteractions(monitoringSourceService);
    }

    @Test
    void 중복된_목록_URL을_등록하면_409를_반환한다() throws Exception {
        given(monitoringSourceService.create(org.mockito.ArgumentMatchers.any(CreateMonitoringSourceRequest.class)))
                .willThrow(new DuplicateMonitoringSourceException());

        mockMvc.perform(post("/api/monitoring-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "조달청",
                                  "boardName": "공지사항",
                                  "listUrl": "https://example.go.kr/notices"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITORING_SOURCE_409_1"));
    }

    @Test
    void 모니터링_소스_목록을_조회한다() throws Exception {
        given(monitoringSourceService.findAll()).willReturn(List.of(response(1L, true)));

        mockMvc.perform(get("/api/monitoring-sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].organizationName").value("조달청"));
    }

    @Test
    void 모니터링_소스를_단건_조회한다() throws Exception {
        given(monitoringSourceService.findById(1L)).willReturn(response(1L, true));

        mockMvc.perform(get("/api/monitoring-sources/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceId").value(1))
                .andExpect(jsonPath("$.data.boardName").value("공지사항"));
    }

    @Test
    void 존재하지_않는_모니터링_소스를_조회하면_404를_반환한다() throws Exception {
        given(monitoringSourceService.findById(1L)).willThrow(new MonitoringSourceNotFoundException());

        mockMvc.perform(get("/api/monitoring-sources/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITORING_SOURCE_404_1"));
    }

    @Test
    void 모니터링_소스를_수정한다() throws Exception {
        given(monitoringSourceService.update(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(UpdateMonitoringSourceRequest.class)
        )).willReturn(response(1L, false));

        mockMvc.perform(put("/api/monitoring-sources/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "조달청",
                                  "boardName": "공지사항",
                                  "description": "조달청 공지 모니터링",
                                  "listUrl": "https://example.go.kr/notices",
                                  "urlIncludePattern": "/notice/view",
                                  "detailFetchCount": 3,
                                  "enabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceId").value(1))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void 수정_필수값이_비어있으면_400을_반환한다() throws Exception {
        mockMvc.perform(put("/api/monitoring-sources/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.organizationName").exists())
                .andExpect(jsonPath("$.data.boardName").exists())
                .andExpect(jsonPath("$.data.listUrl").exists())
                .andExpect(jsonPath("$.data.detailFetchCount").exists())
                .andExpect(jsonPath("$.data.enabled").exists());
    }

    @Test
    void 중복된_목록_URL로_수정하면_409를_반환한다() throws Exception {
        given(monitoringSourceService.update(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(UpdateMonitoringSourceRequest.class)
        )).willThrow(new DuplicateMonitoringSourceException());

        mockMvc.perform(put("/api/monitoring-sources/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "조달청",
                                  "boardName": "공지사항",
                                  "listUrl": "https://example.go.kr/duplicate",
                                  "detailFetchCount": 3,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITORING_SOURCE_409_1"));
    }

    @Test
    void 모니터링_소스를_비활성화한다() throws Exception {
        given(monitoringSourceService.changeEnabled(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(UpdateMonitoringSourceEnabledRequest.class)
        )).willReturn(response(1L, false));

        mockMvc.perform(patch("/api/monitoring-sources/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void 활성_여부가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(patch("/api/monitoring-sources/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.enabled").exists());
    }

    @Test
    void 존재하지_않는_소스의_활성_상태를_변경하면_404를_반환한다() throws Exception {
        given(monitoringSourceService.changeEnabled(
                org.mockito.ArgumentMatchers.eq(999L),
                org.mockito.ArgumentMatchers.any(UpdateMonitoringSourceEnabledRequest.class)
        )).willThrow(new MonitoringSourceNotFoundException());

        mockMvc.perform(patch("/api/monitoring-sources/999/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": false
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITORING_SOURCE_404_1"));
    }

    private MonitoringSourceResponse response(Long sourceId, boolean enabled) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 11, 10, 0);
        return new MonitoringSourceResponse(
                sourceId,
                "조달청",
                "공지사항",
                "조달청 공지 모니터링",
                "https://example.go.kr/notices",
                "/notice/view",
                3,
                enabled,
                now,
                now
        );
    }
}
