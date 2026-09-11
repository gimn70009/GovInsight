package com.publicmonitor.backend.domain.document.web.dto;

import java.util.List;

public record ProposalWriteResponse(String status, String fileName, boolean usesDemoProfile,
        List<Section> sections, String message) {
    public record Section(String title, String body, String sourceQuote, String selectionReason,
            List<String> companyEvidence, List<String> confirmationItems) {}

    public static ProposalWriteResponse unavailable() {
        return new ProposalWriteResponse("UNAVAILABLE", "", false, List.of(),
                "초안 생성을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
