package com.publicmonitor.backend.domain.monitoring.web.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.exception.NoActiveMonitoringSourceException;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringRunService;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringSourceService;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringRunResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunSummaryResponse;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class MonitoringRunControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitoringRunService monitoringRunService;

    @MockitoBean
    private MonitoringSourceService monitoringSourceService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void 수동_모니터링_실행을_생성하면_201을_반환한다() throws Exception {
        LocalDateTime requestedAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        given(monitoringRunService.create(MonitoringTriggerType.MANUAL))
                .willReturn(new CreateMonitoringRunResponse(
                        1L,
                        MonitoringRunStatus.REQUESTED,
                        MonitoringTriggerType.MANUAL,
                        2,
                        requestedAt
                ));

        mockMvc.perform(post("/api/monitoring-runs"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS_201"))
                .andExpect(jsonPath("$.data.runId").value(1))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.triggerType").value("MANUAL"))
                .andExpect(jsonPath("$.data.totalSourceCount").value(2))
                .andExpect(jsonPath("$.data.requestedAt").value("2026-08-12T10:00:00"));
    }

    @Test
    void 활성_소스가_없으면_실행_생성은_409를_반환한다() throws Exception {
        given(monitoringRunService.create(MonitoringTriggerType.MANUAL))
                .willThrow(new NoActiveMonitoringSourceException());

        mockMvc.perform(post("/api/monitoring-runs"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITORING_RUN_409_1"));
    }

    @Test
    void 실행_이력_목록을_조회한다() throws Exception {
        LocalDateTime requestedAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        given(monitoringRunService.findAll()).willReturn(List.of(
                new MonitoringRunSummaryResponse(
                        1L,
                        requestedAt,
                        MonitoringRunStatus.COMPLETED,
                        2,
                        5,
                        1
                )
        ));

        mockMvc.perform(get("/api/monitoring-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].runId").value(1))
                .andExpect(jsonPath("$.data[0].requestedAt").value("2026-08-12T10:00:00"))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].detectedDocumentCount").value(5))
                .andExpect(jsonPath("$.data[0].warningCount").value(1))
                .andExpect(jsonPath("$.data[0].triggerType").doesNotExist())
                .andExpect(jsonPath("$.data[0].successSourceCount").doesNotExist())
                .andExpect(jsonPath("$.data[0].failedSourceCount").doesNotExist())
                .andExpect(jsonPath("$.data[0].completedAt").doesNotExist());
    }
}
