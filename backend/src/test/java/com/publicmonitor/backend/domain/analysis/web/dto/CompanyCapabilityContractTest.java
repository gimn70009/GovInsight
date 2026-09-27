package com.publicmonitor.backend.domain.analysis.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse;
import jakarta.validation.Validation;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CompanyCapabilityContractTest {
    @Test
    void storedCompanyReferenceSurvivesTheInternalAndPublicContracts() {
        var mapper = new ObjectMapper();
        String json = """
                {"companyEvidenceId":"service:1",
                 "confirmedFact":"회사는 제조 AI 에이전트 설계·개발 서비스를 제공합니다.",
                 "strategicInterpretation":"공고의 제조 AI 실증 과업 설계에 활용합니다."}
                """;
        var incoming = mapper.readValue(json, AnalysisResultRequest.StrategyCapabilityMatch.class);
        String saved = mapper.writeValueAsString(incoming);
        var outgoing = mapper.readValue(saved, DocumentDetectionDetailResponse.StrategyCapabilityMatch.class);
        assertThat(outgoing.companyEvidenceId()).isEqualTo("service:1");
        assertThat(outgoing.confirmedFact()).isEqualTo(incoming.confirmedFact());
        assertThat(outgoing.strategicInterpretation()).isEqualTo(incoming.strategicInterpretation());
    }

    @Test
    void oldSavedFactsRemainReadableWithoutClaimingACompanyReference() {
        var legacy = new ObjectMapper().readValue("""
                {"confirmedFact":"공고에서 결산재무제표 제출을 요구합니다.",
                 "strategicInterpretation":"담당자가 제출 자료를 확인합니다."}
                """, DocumentDetectionDetailResponse.StrategyCapabilityMatch.class);
        assertThat(legacy.companyEvidenceId()).isNull();
        assertThat(legacy.confirmedFact()).contains("결산재무제표");
    }

    @Test
    void noRelatedCompanyCapabilityIsAllowedWithoutRelaxingMaximumCount() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validateValue(AnalysisResultRequest.StrategyOnePage.class,
                    "capabilityMatches", List.of())).isEmpty();
            assertThat(validator.validateValue(AnalysisResultRequest.StrategyOnePage.class,
                    "capabilityMatches", null)).isNotEmpty();
            assertThat(validator.validateValue(AnalysisResultRequest.StrategyOnePage.class,
                    "capabilityMatches", Collections.nCopies(5, null))).isNotEmpty();
        }
    }
}
