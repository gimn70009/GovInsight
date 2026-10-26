package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultRequest;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse;
import jakarta.validation.Validation;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SubmissionReviewContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void newReviewNotesSurviveStorageAndDetailDeserialization() {
        String json = """
                {"meetingAgenda":["참여 역할을 결정합니다."],
                 "submissionReviewNotes":["제출 여부 확인: 계획서 — 대상과 조건을 확인합니다."],
                 "submissionDocuments":[],"eligibilityChecklist":[]}
                """;
        var request = mapper.readValue(json, AnalysisResultRequest.Preparation.class);
        var response = mapper.readValue(mapper.writeValueAsString(request),
                DocumentDetectionDetailResponse.Preparation.class);
        assertThat(response.meetingAgenda()).containsExactly("참여 역할을 결정합니다.");
        assertThat(response.submissionReviewNotes()).containsExactlyElementsOf(request.submissionReviewNotes());
        assertThat(response.submissionDocuments()).isEmpty();
        var legacy = mapper.readValue("{\"meetingAgenda\":[]}", DocumentDetectionDetailResponse.Preparation.class);
        assertThat(legacy.submissionReviewNotes()).isNull();
    }

    @Test
    void reviewBudgetIsIndependentOfTheTwentyMeetingDecisions() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var valid = preparation(Collections.nCopies(64, "확인할 서류"));
            assertThat(validator.validateProperty(valid, "meetingAgenda")).isEmpty();
            assertThat(validator.validateProperty(valid, "submissionReviewNotes")).isEmpty();
            assertThat(validator.validateProperty(preparation(null), "submissionReviewNotes")).isEmpty();
            for (var notes : List.of(Collections.nCopies(65, "확인할 서류"), List.of("가".repeat(501)), List.of(""))) {
                assertThat(validator.validateProperty(preparation(notes), "submissionReviewNotes")).isNotEmpty();
            }
        }
    }

    private AnalysisResultRequest.Preparation preparation(List<String> notes) {
        return new AnalysisResultRequest.Preparation(Collections.nCopies(8, "참여 역할 결정"), notes,
                List.of(), List.of(), null, null);
    }
}
