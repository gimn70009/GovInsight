package com.publicmonitor.backend.domain.analysis.web.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisPreparationValidationTest {
    @Test
    void acceptsReclassifiedDocumentsAndRejectsOverflow() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (int count : new int[]{16, 27}) {
                assertThat(validator.validateValue(AnalysisResultRequest.Preparation.class,
                        "submissionDocuments", Collections.nCopies(count, null))).isEmpty();
            }
            assertThat(validator.validateValue(AnalysisResultRequest.Preparation.class,
                    "submissionDocuments", Collections.nCopies(28, null))).hasSize(1);
        }
    }
}