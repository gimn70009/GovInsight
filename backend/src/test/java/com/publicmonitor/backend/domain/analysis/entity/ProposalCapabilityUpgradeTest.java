package com.publicmonitor.backend.domain.analysis.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProposalCapabilityUpgradeTest {
    @Test
    void oldChecklistVersionsRequireUpgradeButCurrentResultsCanBeReused() {
        assertThat(analysis(12, "PROPOSAL_REQUEST").requiresProposalSchemaUpgrade()).isTrue();
        assertThat(analysis(13, "PROPOSAL_REQUEST").requiresProposalSchemaUpgrade()).isTrue();
        assertThat(analysis(14, "PROPOSAL_REQUEST").requiresProposalSchemaUpgrade()).isTrue();
        assertThat(analysis(15, "PROPOSAL_REQUEST").requiresProposalSchemaUpgrade()).isTrue();
        assertThat(analysis(16, "PROPOSAL_REQUEST").requiresProposalSchemaUpgrade()).isFalse();
        assertThat(analysis(12, "BUSINESS_NOTICE").requiresProposalSchemaUpgrade()).isFalse();
    }

    private DocumentAnalysis analysis(int version, String type) {
        var analysis = new DocumentAnalysis();
        ReflectionTestUtils.setField(analysis, "proposalDirection",
                "{\"documentType\":\"" + type + "\",\"preparationSchemaVersion\":" + version
                        + ",\"draftStatus\":\"READY\",\"preparation\":{\"strategy\":{\"capabilityMatches\":[]}}}");
        return analysis;
    }
}
