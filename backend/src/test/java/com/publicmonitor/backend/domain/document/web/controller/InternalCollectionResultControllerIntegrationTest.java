package com.publicmonitor.backend.domain.document.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.service.CollectionResultService;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultResponse;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultResponse.DocumentResult;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringRunService;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringSourceService;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
class InternalCollectionResultControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CollectionResultService collectionResultService;
    @MockitoBean MonitoringRunService monitoringRunService;
    @MockitoBean MonitoringSourceService monitoringSourceService;
    @MockitoBean UserRepository userRepository;

    @Test
    void 인증_토큰_없이_Python_수집_결과를_수신한다() throws Exception {
        given(collectionResultService.receive(any())).willReturn(new CollectionResultResponse(List.of(
                new DocumentResult(
                        "https://example.com/notice/1",
                        100L,
                        200L,
                        DocumentChangeType.NEW_DOCUMENT,
                        true
                )
        )));

        mockMvc.perform(post("/internal/monitoring/collection-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "runId": 10,
                                  "jobId": "3ed1132b-8d61-45d9-bfab-06c1ed96f202",
                                  "sources": [{
                                    "sourceId": 1,
                                    "status": "COMPLETED",
                                    "errorMessage": null,
                                    "documents": [{
                                      "originalUrl": "https://example.com/notice/1",
                                      "externalDocumentId": "1",
                                      "title": "지원사업 공고",
                                      "contentText": "공고 본문",
                                      "publishedAt": "2026-08-18T09:00:00",
                                      "attachments": []
                                    }]
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents[0].documentId").value(100))
                .andExpect(jsonPath("$.data.documents[0].changeType").value("NEW_DOCUMENT"))
                .andExpect(jsonPath("$.data.documents[0].analysisRequired").value(true));
    }

    @Test
    void 필수값이_없으면_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(post("/internal/monitoring/collection-results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId": 10, "jobId": "", "sources": []}
                                """))
                .andExpect(status().isBadRequest());
    }
}
