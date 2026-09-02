package com.publicmonitor.backend.domain.document.web.dto;

import java.util.List;

public record SimilarNoticeResponse(
        ComparisonSide currentNotice,
        List<SimilarNotice> similarNotices
) {
    public record SimilarNotice(
            Long detectionId,
            int similarityScore,
            String title,
            String originalUrl,
            ComparisonSide comparison,
            String commonPoints,
            String proposalReuse,
            String caution
    ) {
    }

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
