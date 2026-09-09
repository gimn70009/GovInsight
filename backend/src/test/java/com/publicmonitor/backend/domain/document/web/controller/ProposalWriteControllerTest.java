package com.publicmonitor.backend.domain.document.web.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.service.ProposalSourceService;
import com.publicmonitor.backend.domain.document.service.ProposalDraftStore;
import com.publicmonitor.backend.domain.user.entity.User;
import com.publicmonitor.backend.global.security.CustomUserDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.junit.jupiter.api.AfterEach;
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
    private final ProposalDraftStore drafts = mock(ProposalDraftStore.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(new CustomUserDetails(user), null, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new ProposalWriteController(sources, writer, drafts))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 목록과_초안을_공통_응답으로_반환한다() throws Exception {
        when(sources.list(1L)).thenReturn(List.of(
                new ProposalSourceResponse(2L, 0, "양식.hwpx", "양식.zip", true, "")));
        mvc.perform(get("/api/document-detections/1/proposal-sources"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].attachmentId").value(2))
                .andExpect(jsonPath("$.data[0].available").value(true));
        when(writer.write(eq(7L), eq(1L), any())).thenReturn(
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
        when(writer.write(eq(7L), eq(1L), any())).thenThrow(new DocumentDetectionException(
                DocumentDetectionResponseCode.PROPOSAL_SOURCE_NOT_FOUND));
        mvc.perform(post("/api/document-detections/1/proposal-draft").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachmentId\":99,\"partIndex\":0}"))
                .andExpect(status().isNotFound());
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void 저장한_초안_조회와_최근_선택은_로그인_계정으로_처리한다() throws Exception {
        var result = new ProposalWriteResponse("COMPLETED", "양식.hwpx", false, List.of(), "");
        when(drafts.list(7L, 1L)).thenReturn(List.of(new SavedProposalDraftResponse(2L, 0, "양식.zip",
                null, null, result)));
        mvc.perform(get("/api/document-detections/1/proposal-drafts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].result.fileName").value("양식.hwpx"));
        mvc.perform(put("/api/document-detections/1/proposal-drafts/last-viewed")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attachmentId\":2,\"partIndex\":0}"))
                .andExpect(status().isOk());
        verify(drafts).markViewed(7L, 1L, new ProposalWriteRequest(2L, 0));
        verifyNoInteractions(writer);
    }

    @Test
    void 진행상태는_로그인계정으로_조회하고_없는_감지는_거절한다() throws Exception {
        when(writer.state(7L, 1L)).thenReturn(new ProposalDraftStateResponse(List.of(),
                List.of(new ProposalDraftStateResponse.RunningProposalResponse(2L, 1))));
        mvc.perform(get("/api/document-detections/1/proposal-drafts/state"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.running[0].attachmentId").value(2))
                .andExpect(jsonPath("$.data.running[0].partIndex").value(1))
                .andExpect(jsonPath("$.data.drafts").isEmpty());
        verify(writer).state(7L, 1L);
        when(writer.state(7L, 99L)).thenThrow(new DocumentDetectionException(DocumentDetectionResponseCode.NOT_FOUND));
        mvc.perform(get("/api/document-detections/99/proposal-drafts/state"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 저장되지_않은_초안은_최근_선택으로_기록할_수_없다() throws Exception {
        doThrow(new DocumentDetectionException(DocumentDetectionResponseCode.PROPOSAL_DRAFT_NOT_FOUND))
                .when(drafts).markViewed(eq(7L), eq(1L), any());
        mvc.perform(put("/api/document-detections/1/proposal-drafts/last-viewed")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attachmentId\":2,\"partIndex\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/document-detections/1/proposal-drafts/last-viewed")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"attachmentId\":0,\"partIndex\":-1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(writer);
    }
}
