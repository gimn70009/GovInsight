package com.publicmonitor.backend.domain.document.web.dto;

import java.util.List;

public record SimilarNoticeResponse(
        ComparisonSide currentNotice,
        List<SimilarNotice> similarNotices
) {
    public record SimilarNotice(
            Long detectionId,
            Integer similarityScore,
            String title,
            String originalUrl,
            ComparisonSide comparison,
            LegalReview legalReview,
            String matchBasis
    ) {
    }

    public record LegalReview(
            String overallStatus,
            String summary,
            List<LegalRiskCheck> checks,
            String disclaimer
    ) {
    }

    public record LegalRiskCheck(
            String type,
            String label,
            String status,
            String finding,
            String evidence,
            List<VerifiedLegalEvidence> verifiedEvidence
    ) {
    }

    public record VerifiedLegalEvidence(String side, String interpretation, String excerpt) {}

    public record ComparisonSide(
            String organizationName,
            String purpose,
            String supportScale,
            String applicationDeadline,
            String eligibility,
            String requiredPartner
    ) {
    }
}
