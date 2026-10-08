package com.publicmonitor.backend.domain.monitoring.web.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringScheduleFrequency;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringScheduleChangedException;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringScheduleService;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringScheduleResponse;
import com.publicmonitor.backend.global.config.SecurityConfig;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = MonitoringScheduleController.class, properties = {
        "app.monitoring.schedule.enabled=true",
        "app.jwt.secret=VGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS10aGF0LWlzLWxvbmc=",
        "app.jwt.access-token-expiration=1h"
})
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class MonitoringScheduleControllerTest {

    private static final LocalDateTime RESERVED = LocalDateTime.of(2026, 10, 8, 14, 0);

    @Autowired MockMvc mvc;
    @MockitoBean MonitoringScheduleService schedules;
    @MockitoBean UserDetailsService users;

    @Test
    void 예약_대기_시각을_한국_시간으로_반환한다() throws Exception {
        given(schedules.find()).willReturn(response(RESERVED));
        mvc.perform(get("/api/monitoring-schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingScheduledAt").value("2026-10-08T14:00:00"))
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void 확인한_대기만_취소하고_갱신된_설정을_반환한다() throws Exception {
        given(schedules.cancelPending(RESERVED)).willReturn(response(null));
        mvc.perform(delete("/api/monitoring-schedule/pending").param("scheduledAt", RESERVED.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingScheduledAt").isEmpty())
                .andExpect(jsonPath("$.data.enabled").value(true));
        verify(schedules).cancelPending(RESERVED);
    }

    @Test
    void 취소_대상_시각이_누락되면_400을_반환한다() throws Exception {
        mvc.perform(delete("/api/monitoring-schedule/pending"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(schedules);
    }

    @Test
    void 잘못된_취소_대상_시각이면_400을_반환한다() throws Exception {
        mvc.perform(delete("/api/monitoring-schedule/pending").param("scheduledAt", "tomorrow"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(schedules);
    }

    @Test
    void 이미_시작되거나_바뀐_대기는_취소_성공으로_표시하지_않는다() throws Exception {
        given(schedules.cancelPending(RESERVED)).willThrow(new MonitoringScheduleChangedException());
        mvc.perform(delete("/api/monitoring-schedule/pending").param("scheduledAt", RESERVED.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITORING_SCHEDULE_409_1"));
    }

    @Test
    @WithAnonymousUser
    void 로그인하지_않으면_대기_조회와_취소가_차단된다() throws Exception {
        mvc.perform(get("/api/monitoring-schedule")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/monitoring-schedule/pending").param("scheduledAt", RESERVED.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(schedules);
    }

    private MonitoringScheduleResponse response(LocalDateTime pending) {
        return new MonitoringScheduleResponse(true, MonitoringScheduleFrequency.DAILY,
                LocalTime.of(14, 0), Set.of(), pending);
    }
}
