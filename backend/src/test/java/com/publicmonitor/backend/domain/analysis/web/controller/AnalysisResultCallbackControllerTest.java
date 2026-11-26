package com.publicmonitor.backend.domain.analysis.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.publicmonitor.backend.domain.analysis.service.AnalysisResultService;
import com.publicmonitor.backend.domain.analysis.service.AnalysisTaskService;
import com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultRequest;
import com.publicmonitor.backend.global.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@WebMvcTest(controllers = {InternalAnalysisResultController.class, InternalAnalysisResultFailureController.class},
        properties = {"app.jwt.secret=VGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS10aGF0LWlzLWxvbmc=", "app.jwt.access-token-expiration=1h"})
@Import(SecurityConfig.class)
class AnalysisResultCallbackControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AnalysisResultService results;
    @MockitoBean AnalysisTaskService tasks;
    @MockitoBean UserDetailsService users;

    private ObjectNode payload(String excerpt) {
        var body = (ObjectNode) mapper.readTree("""
                {"runId":1,"jobId":"17c6e64f-0411-48cb-9d61-a98f3d36b195","results":[],"failures":[],
                 "legalResults":[{"detectionId":13,"documentId":13,"versionId":13,
                  "legalRisks":[{"type":"RESULT_IP_REUSE","status":"ASSESSMENT_INCOMPLETE",
                  "summary":"원문 근거의 검토가 필요합니다."}]}]}
                """);
        ((ObjectNode) body.path("legalResults").get(0).path("legalRisks").get(0)).put("evidenceExcerpt", excerpt);
        return body;
    }

    @Test void supplementaryHwpSymbolsSurviveHttpDecodingAndValidationUnchanged() throws Exception {
        String excerpt = "가".repeat(2976) + new String(Character.toChars(0xF02B1)).repeat(19);
        mvc.perform(post("/internal/monitoring/analysis-results").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload(excerpt)))).andExpect(status().isOk());
        var captured = ArgumentCaptor.forClass(AnalysisResultRequest.class);
        verify(results).receive(captured.capture());
        assertThat(captured.getValue().legalResults().getFirst().legalRisks().getFirst().evidenceExcerpt()).isEqualTo(excerpt);
    }

    @Test void actualCodePointOverflowStillReturns400BeforePersistence() throws Exception {
        mvc.perform(post("/internal/monitoring/analysis-results").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload("가".repeat(3001)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data['legalResults[0].legalRisks[0].evidenceExcerpt']").exists());
        verifyNoInteractions(results);
    }

    @Test void rejectionEndpointAcceptsAndAcknowledgesMatchingWork() throws Exception {
        when(tasks.resultRejected(any())).thenReturn(true);
        mvc.perform(post("/internal/monitoring/analysis-result-failures").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"runId":1,"jobId":"17c6e64f-0411-48cb-9d61-a98f3d36b195","phase":"ANALYSIS","httpStatus":400}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(true));
        verify(tasks).resultRejected(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"runId\":0}",
            "{\"runId\":1,\"jobId\":\"invalid\",\"phase\":\"ANALYSIS\",\"httpStatus\":400}",
            "{\"runId\":1,\"jobId\":\"17c6e64f-0411-48cb-9d61-a98f3d36b195\",\"phase\":\"UNKNOWN\",\"httpStatus\":400}",
            "{\"runId\":1,\"jobId\":\"17c6e64f-0411-48cb-9d61-a98f3d36b195\",\"phase\":\"ANALYSIS\",\"httpStatus\":500}"})
    void malformedRejectionNoticesNeverReachStateChanges(String body) throws Exception {
        mvc.perform(post("/internal/monitoring/analysis-result-failures").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(tasks);
    }
}
