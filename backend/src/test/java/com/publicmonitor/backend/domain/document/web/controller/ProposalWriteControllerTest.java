package com.publicmonitor.backend.domain.document.web.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.service.ProposalSourceService;
import com.publicmonitor.backend.domain.document.service.ProposalWriteService;
import com.publicmonitor.backend.domain.document.web.dto.*;
import com.publicmonitor.backend.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProposalWriteControllerTest {
    private final ProposalSourceService sources = mock(ProposalSourceService.class);
    private final ProposalWriteService writer = mock(ProposalWriteService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ProposalWriteController(sources, writer))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 목록과_초안을_공통_응답으로_반환한다() throws Exception {
        when(sources.list(1L)).thenReturn(List.of(
                new ProposalSourceResponse(2L, 0, "양식.hwpx", "양식.zip", true, "")));
        mvc.perform(get("/api/document-detections/1/proposal-sources"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].attachmentId").value(2))
                .andExpect(jsonPath("$.data[0].available").value(true));
        when(writer.write(eq(1L), any())).thenReturn(
                new ProposalWriteResponse("COMPLETED", "양식.hwpx", true, List.of(
                        new ProposalWriteResponse.Section("사업목표", "제안 본문입니다.", "사업목표",
                                "평가 항목입니다.", List.of("제조 AI"), List.of("예산 확인"))), ""));
        mvc.perform(post("/api/document-detections/1/proposal-draft").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":2,\"partIndex\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.usesDemoProfile").value(true))
                .andExpect(jsonPath("$.data.sections[0].sourceQuote").value("사업목표"));
    }

    @Test
    void 잘못된_선택값은_모델_호출_전에_거절한다() throws Exception {
        for (String body : List.of("{}", "{\"attachmentId\":0}",
                "{\"attachmentId\":2,\"partIndex\":-1}")) {
            mvc.perform(post("/api/document-detections/1/proposal-draft")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(writer);
    }

    @Test
    void 다른_문서의_첨부는_404로_반환한다() throws Exception {
        when(writer.write(eq(1L), any())).thenThrow(new DocumentDetectionException(
                DocumentDetectionResponseCode.PROPOSAL_SOURCE_NOT_FOUND));
        mvc.perform(post("/api/document-detections/1/proposal-draft").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":99,\"partIndex\":0}"))
                .andExpect(status().isNotFound());
    }
}
