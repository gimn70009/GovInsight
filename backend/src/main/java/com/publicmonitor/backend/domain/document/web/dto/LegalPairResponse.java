package com.publicmonitor.backend.domain.document.web.dto;

import java.util.List;

public record LegalPairResponse(String status, List<Insight> insights, String message, Boolean usesDemoProfile) {
    public LegalPairResponse {
        usesDemoProfile = Boolean.TRUE.equals(usesDemoProfile);
    }
    public LegalPairResponse(String status, List<Insight> insights, String message) {
        this(status, insights, message, false);
    }
    public record Insight(String type, String comparison, String implication, String verification, List<Integer> evidenceIds) {}
    public static LegalPairResponse unavailable() {
        return new LegalPairResponse("UNAVAILABLE", List.of(), "추가 해석을 완료하지 못했습니다. 공고별 결과를 확인해 주세요.");
    }
}
