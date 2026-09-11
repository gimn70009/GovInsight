package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PreparationCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesLegacyInformationInThreeListsWithoutChangingStoredJson() {
        String original = """
                {"preparation":{"meetingAgenda":["참여 역할을 결정합니다."],
                "eligibilityChecklist":[],"submissionDocuments":[],"companyInputs":[
                  {"title":"중소기업 자격", "detail":"자격 확인 필요", "nextAction":"규모를 확인합니다.",
                   "requirementLevel":"MANDATORY","source":{"origin":"ATTACHMENT","excerpt":"중소기업만 신청"}},
                  {"title":"사업자등록증", "detail":"자료 발급", "nextAction":"자료를 발급합니다."},
                  {"title":"인력 배정", "detail":"가용 인력을 모릅니다.", "nextAction":"투입 인력을 결정합니다.",
                   "source":{"attachmentName":"양식.pdf","location":"3쪽","excerpt":"참여 인력 작성"}}
                ]}}
                """;
        var result = mapper.readTree(PreparationCompatibility.normalize(original, mapper)).path("preparation");
        assertThat(result.has("companyInputs")).isFalse();
        assertThat(result.path("eligibilityChecklist").get(0).path("source").path("excerpt").asText())
                .isEqualTo("중소기업만 신청");
        assertThat(result.path("submissionDocuments").get(0).path("title").asText()).isEqualTo("사업자등록증");
        assertThat(result.path("meetingAgenda").get(1).asText())
                .contains("인력 배정", "가용 인력을 모릅니다.", "투입 인력을 결정합니다.", "양식.pdf", "3쪽", "참여 인력 작성");
        assertThat(original).contains("companyInputs");
    }

    @Test
    void preservesNewFormatAndMissingPreparation() {
        String json = "{\"preparation\":{\"meetingAgenda\":[],\"eligibilityChecklist\":[],\"submissionDocuments\":[]}}";
        assertThat(mapper.readTree(PreparationCompatibility.normalize(json, mapper))).isEqualTo(mapper.readTree(json));
        assertThat(PreparationCompatibility.normalize("{\"preparation\":null}", mapper)).isEqualTo("{\"preparation\":null}");
    }
}
