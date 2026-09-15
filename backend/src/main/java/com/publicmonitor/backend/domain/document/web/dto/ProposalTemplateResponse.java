package com.publicmonitor.backend.domain.document.web.dto;

import java.util.List;

public record ProposalTemplateResponse(String status, List<String> sectionTitles, String message) {
    public static ProposalTemplateResponse unavailable() {
        return new ProposalTemplateResponse("UNAVAILABLE", List.of(),
                "작성란 확인을 완료하지 못했습니다. 다시 확인해 주세요.");
    }
}
