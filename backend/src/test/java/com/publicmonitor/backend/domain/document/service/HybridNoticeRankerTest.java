package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HybridNoticeRankerTest {
    @Test
    void lexicalAcceptanceIsSymmetricForFocusedAndBroadNotices() {
        Set<String> narrowTitle = Set.of("공동연구");
        Set<String> narrowPurpose = Set.of("기술협력", "해외진출");
        Set<String> broadTitle = Set.of("공동연구", "다자협력", "양자협력");
        Set<String> broadPurpose = Set.of("기술협력", "해외진출", "센터", "인력", "투자", "네트워크");
        var narrow = new HybridNoticeRanker.Candidate(1, .70, narrowTitle, narrowPurpose, List.of("공동연구"));
        var broad = new HybridNoticeRanker.Candidate(2, .70, broadTitle, broadPurpose, List.of("공동연구"));
        assertThat(HybridNoticeRanker.rank(narrowTitle, narrowPurpose, List.of(broad))).hasSize(1);
        assertThat(HybridNoticeRanker.rank(broadTitle, broadPurpose, List.of(narrow))).hasSize(1);
    }

    @Test
    void semanticParaphraseWithoutSharedWordsIsRetrieved() {
        var candidate = new HybridNoticeRanker.Candidate(1, .95,
                Set.of("it", "인프라"), Set.of("유지관리"), List.of());
        assertThat(HybridNoticeRanker.rank(Set.of("전산장비"), Set.of("유지보수"), List.of(candidate)))
                .singleElement().satisfies(m -> assertThat(m.basis()).isEqualTo("SEMANTIC"));
    }

    @Test
    void strongPurposeAndLexicalEvidenceCanRecoverModerateSemanticScore() {
        var candidate = new HybridNoticeRanker.Candidate(1, .70,
                Set.of("반도체"), Set.of("결함탐지", "공정"), List.of("반도체", "결함탐지", "공정"));
        assertThat(HybridNoticeRanker.rank(Set.of("반도체"), Set.of("결함탐지", "공정"), List.of(candidate)))
                .singleElement().satisfies(m -> assertThat(m.basis()).isEqualTo("LEXICAL"));
    }

    @Test
    void titleOverlapAloneCannotRecoverWeakSemantics() {
        var candidate = new HybridNoticeRanker.Candidate(1, .70,
                Set.of("반도체"), Set.of("채용", "교육"), List.of("반도체"));
        assertThat(HybridNoticeRanker.rank(Set.of("반도체"), Set.of("결함탐지", "공정"), List.of(candidate))).isEmpty();
    }

    @Test
    void obviousThemeMismatchIsNotRescuedBySemanticOnlyLane() {
        var candidate = new HybridNoticeRanker.Candidate(1, .99,
                Set.of("공연"), Set.of("창작"), List.of());
        assertThat(HybridNoticeRanker.rank(Set.of("반도체"), Set.of("결함탐지"), List.of(candidate))).isEmpty();
    }

    @Test
    void marginalSemanticOnlyResultAndEmptyQueryAreRejected() {
        var candidate = new HybridNoticeRanker.Candidate(1, .85,
                Set.of("it"), Set.of("유지관리"), List.of());
        assertThat(HybridNoticeRanker.rank(Set.of("전산장비"), Set.of("유지보수"), List.of(candidate))).isEmpty();
        assertThat(HybridNoticeRanker.rank(Set.of(), Set.of(), List.of(candidate))).isEmpty();
    }

    @Test
    void rankFusionRewardsAgreementWithoutChangingSemanticScore() {
        var semanticOnly = new HybridNoticeRanker.Candidate(1, .99,
                Set.of("it"), Set.of("유지관리"), List.of());
        var both = new HybridNoticeRanker.Candidate(2, .85,
                Set.of("전산장비"), Set.of("유지보수"), List.of("전산장비", "유지보수"));
        assertThat(HybridNoticeRanker.rank(Set.of("전산장비"), Set.of("유지보수"), List.of(semanticOnly, both)))
                .extracting(HybridNoticeRanker.Match::id).containsExactly(2L, 1L);
        assertThat(both.semantic()).isEqualTo(.85);
    }

    @Test
    void lexicalLaneCanRetrieveOutsideSemanticTopTwenty() {
        var candidates = new java.util.ArrayList<HybridNoticeRanker.Candidate>();
        for (int i = 1; i <= 25; i++) {
            candidates.add(new HybridNoticeRanker.Candidate(i, .95,
                    Set.of("공연"), Set.of("창작"), List.of()));
        }
        candidates.add(new HybridNoticeRanker.Candidate(99, .70,
                Set.of("반도체"), Set.of("공정", "결함탐지"), List.of("반도체", "공정", "결함탐지")));
        assertThat(HybridNoticeRanker.rank(Set.of("반도체"), Set.of("공정", "결함탐지"), candidates))
                .extracting(HybridNoticeRanker.Match::id).containsExactly(99L);
    }
    @Test
    void missingEmbeddingCanUseStrongPurposeButNotTitleOnly() {
        var match = new HybridNoticeRanker.Candidate(1, Double.NaN, Set.of("반도체"),
                Set.of("공정", "결함탐지", "예지보전"), List.of("반도체"));
        assertThat(HybridNoticeRanker.rank(Set.of("반도체"), Set.of("공정", "결함탐지", "예지보전"), List.of(match)))
                .singleElement().satisfies(item -> assertThat(item.basis()).isEqualTo("LEXICAL_ONLY"));
        assertThat(HybridNoticeRanker.rank(Set.of("반도체"), Set.of("교육", "훈련", "인력"), List.of(match))).isEmpty();
    }

}
