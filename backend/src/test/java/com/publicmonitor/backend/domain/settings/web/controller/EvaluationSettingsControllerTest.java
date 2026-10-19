package com.publicmonitor.backend.domain.settings.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.publicmonitor.backend.domain.settings.exception.*;
import com.publicmonitor.backend.domain.settings.service.EvaluationSettingsService;
import com.publicmonitor.backend.global.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;
import com.publicmonitor.backend.domain.settings.web.dto.UpdateEvaluationSettingsRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@WebMvcTest(controllers = EvaluationSettingsController.class, properties = {
        "app.jwt.secret=VGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS10aGF0LWlzLWxvbmc=",
        "app.jwt.access-token-expiration=1h"
})
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class EvaluationSettingsControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean EvaluationSettingsService service;
    @MockitoBean UserDetailsService users;

    private ObjectNode payload() throws Exception {
        try (var input = new ClassPathResource("opportunity-settings-defaults.json").getInputStream()) {
            var root = mapper.createObjectNode().put("revision", 0);
            root.set("data", mapper.readTree(input));
            return root;
        }
    }

    @Test void validSettingsCanBeSubmitted() throws Exception {
        mvc.perform(put("/api/evaluation-settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload()))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {1, 3, 10, 11, 18, 21, 365})
    void customPreparationDaysReachPersistenceUnchanged(Integer days) throws Exception {
        var body = payload();
        ((ObjectNode) body.path("data").path("criteria")).put("preparationDays", days);
        mvc.perform(put("/api/evaluation-settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isOk());
        var request = ArgumentCaptor.forClass(UpdateEvaluationSettingsRequest.class);
        verify(service).update(request.capture());
        assertThat(request.getValue().data().criteria().preparationDays()).isEqualTo(days);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "366", "1.5", "1.0", "true", "\"10\"", "\"abc\"", "\"\""})
    void invalidPreparationDaysNeverReachPersistence(String daysJson) throws Exception {
        var body = payload();
        ((ObjectNode) body.path("data").path("criteria")).set("preparationDays", mapper.readTree(daysJson));
        mvc.perform(put("/api/evaluation-settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"blankName", "futureDate", "emptySelection", "tooManyGoals", "invalidDays", "missingCompany", "missingCriteria"})
    void invalidFormsNeverReachPersistence(String scenario) throws Exception {
        var body = payload();
        var data = (ObjectNode) body.path("data");
        var company = (ObjectNode) data.path("company");
        var criteria = (ObjectNode) data.path("criteria");
        switch (scenario) {
            case "blankName" -> company.put("name", "  ");
            case "futureDate" -> company.put("establishedOn", "2999-01-01");
            case "emptySelection" -> company.putArray("industries");
            case "tooManyGoals" -> criteria.putArray("goals").add("A").add("B").add("C").add("D");
            case "invalidDays" -> criteria.put("preparationDays", 366);
            case "missingCompany" -> data.remove("company");
            case "missingCriteria" -> data.remove("criteria");
        }
        mvc.perform(put("/api/evaluation-settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 7, 10, 14, 18, 30, 365})
    void customUrgencyDaysReachPersistenceUnchanged(int days) throws Exception {
        var body = payload();
        ((ObjectNode) body.path("data").path("criteria")).put("urgencyDays", days);
        mvc.perform(put("/api/evaluation-settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isOk());
        var request = ArgumentCaptor.forClass(UpdateEvaluationSettingsRequest.class);
        verify(service).update(request.capture());
        assertThat(request.getValue().data().criteria().urgencyDays()).isEqualTo(days);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "366", "1.5", "1.0", "true", "\"10\"", "\"abc\"", "\"\""})
    void invalidUrgencyDaysNeverReachPersistence(String daysJson) throws Exception {
        var body = payload();
        ((ObjectNode) body.path("data").path("criteria")).set("urgencyDays", mapper.readTree(daysJson));
        mvc.perform(put("/api/evaluation-settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void versionConflictHasRecoverableResponse() throws Exception {
        given(service.update(any())).willThrow(new EvaluationSettingsException(EvaluationSettingsResponseCode.CHANGED));
        mvc.perform(put("/api/evaluation-settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload()))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVALUATION_SETTINGS_409_1"));
    }

    @Test @WithAnonymousUser void authenticationIsRequired() throws Exception {
        mvc.perform(get("/api/evaluation-settings")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/evaluation-settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload()))).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test @WithMockUser(roles = "USER") void onlyAdministratorCanReadOrWriteCompanyInformation() throws Exception {
        mvc.perform(get("/api/evaluation-settings")).andExpect(status().isForbidden());
        mvc.perform(put("/api/evaluation-settings").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload()))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
