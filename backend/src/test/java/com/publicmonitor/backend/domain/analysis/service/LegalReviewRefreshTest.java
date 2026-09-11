package com.publicmonitor.backend.domain.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class LegalReviewRefreshTest {
    private String summary(String status) {
        return "{\"legalReviewVersion\":2,\"legalRisks\":[" + java.util.stream.Stream.of("DUPLICATE_SUPPORT", "COST_DOUBLE_COUNTING",
                "RESULT_IP_REUSE", "CONFIDENTIALITY", "PROPOSAL_TEXT_REUSE")
                .map(type -> "{\"type\":\"" + type + "\",\"status\":\"" + status + "\",\"summary\":\"검토 결과\"}")
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
    }
    @Test void incompleteAndMissingResultsAreRequestedAgain() {
        assertThat(AnalysisJobRequestService.requiresLegalReview(summary("ASSESSMENT_INCOMPLETE"))).isTrue();
        assertThat(AnalysisJobRequestService.requiresLegalReview(null)).isTrue();
        assertThat(AnalysisJobRequestService.requiresLegalReview("{}")).isTrue();
    }
    @Test void completedAndMissingSourceResultsAreNotRepeatedIndefinitely() {
        assertThat(AnalysisJobRequestService.requiresLegalReview(summary("CAUTION"))).isFalse();
        assertThat(AnalysisJobRequestService.requiresLegalReview(summary("DATA_INSUFFICIENT"))).isFalse();
    }
    @Test void oldReviewsAreUpgradedOnceAndSourceLimitsDoNotRepeat() {
        assertThat(AnalysisJobRequestService.requiresLegalReview(summary("NOT_FOUND").replace("\"legalReviewVersion\":2,", ""))).isTrue();
        String blocked = summary("ASSESSMENT_INCOMPLETE").replace("\"summary\":", "\"failureReason\":\"CONTEXT_REQUIRED\",\"summary\":");
        assertThat(AnalysisJobRequestService.requiresLegalReview(blocked)).isFalse();
    }

    @Test void transientFailuresHaveCooldownAndRetryCap() {
        var now = java.time.Instant.parse("2026-09-08T01:00:00Z");
        String failure = summary("ASSESSMENT_INCOMPLETE");
        assertThat(LegalReviewPolicy.pendingTypes(failure.replaceFirst("\\{", "{\"legalReviewedAt\":\"2026-09-08T00:45:00Z\","), now)).isEmpty();
        assertThat(LegalReviewPolicy.pendingTypes(failure.replaceFirst("\\{", "{\"legalReviewedAt\":\"2026-09-08T00:00:00Z\","), now)).hasSize(5);
        assertThat(LegalReviewPolicy.pendingTypes(failure.replaceFirst("\\{", "{\"legalReviewAttempts\":3,"), now)).isEmpty();
    }

}
