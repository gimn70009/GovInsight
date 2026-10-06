package com.publicmonitor.backend.domain.document.web.dto;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProposalBodyPresentationTest {
    @Test void savedDraftDeserializationAndNewResponseStartWithCompanyBody() {
        String body = "본 항목에서는 당사의 첨단산업 인재양성 사업 수행 현황과 운영 역량을 기술합니다. "
                + "당사는 제조 현장의 데이터 통합을 수행합니다.";
        var section = new ProposalWriteResponse.Section("항목", body, "원문", "선정 사유", List.of(), List.of());
        assertThat(section.body()).isEqualTo("당사는 제조 현장의 데이터 통합을 수행합니다.");
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(java.util.Map.of("title", "항목", "body", body,
                "sourceQuote", "원문", "selectionReason", "사유", "companyEvidence", List.of(),
                "confirmationItems", List.of()));
        assertThat(mapper.readValue(json, ProposalWriteResponse.Section.class).body())
                .isEqualTo(section.body());
        assertThat(section.sourceQuote()).isEqualTo("원문");
    }

    @Test void actualPlansAndIncompleteDraftsAreNeverDeleted() {
        for (String body : List.of(
                "본 사업에서는 실무 교육을 제공합니다. 당사는 운영을 지원합니다.",
                "당사는 사업 계획서를 작성합니다.",
                "본 항목에서는 운영 역량을 기술합니다.",
                "본 항목에서는 절차를 설명합니다. 기관은 사업을 주관합니다.")) {
            assertThat(ProposalBodyPresentation.clean(body)).isEqualTo(body);
        }
    }
}
