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

    @Test
    void hybridAndLexicalPathsCannotBypassConflictingThemes() {
        for (double semantic : List.of(.70, .85, .99)) {
            var item = new HybridNoticeRanker.Candidate(1, semantic, Set.of("공연"),
                    Set.of("시설", "확충", "운영"), List.of("시설", "확충", "운영"));
            assertThat(HybridNoticeRanker.rank(Set.of("반도체"), Set.of("시설", "확충", "운영"), List.of(item))).isEmpty();
        }
    }

    @Test
    void aSingleSharedTermDoesNotCorroborateModerateSemantics() {
        var item = new HybridNoticeRanker.Candidate(1, .80, Set.of("반도체"), Set.of("교육", "인력"), List.of("반도체"));
        assertThat(HybridNoticeRanker.rank(Set.of("반도체"), Set.of("결함탐지", "검사"), List.of(item))).isEmpty();
    }

    @Test
    void contaminatedEmbeddingCannotUseSemanticOnlyPath() {
        var query = new HybridNoticeRanker.Features(Set.of("전산장비"), Set.of("유지보수"));
        var features = new HybridNoticeRanker.Features(Set.of("it", "인프라"), Set.of("유지관리"));
        var item = new HybridNoticeRanker.Candidate(1, .99, features, List.of(), false);
        assertThat(HybridNoticeRanker.rank(query, List.of(item), HybridNoticeRanker.Statistics.of(List.of(features)))).isEmpty();
    }

    @Test
    void excludingCurrentDocumentFromCachedStatisticsMatchesFreshRanking() {
        var query = new HybridNoticeRanker.Features(Set.of("반도체"), Set.of("공정", "결함탐지"));
        var candidates = new java.util.ArrayList<HybridNoticeRanker.Candidate>();
        var corpus = new java.util.ArrayList<HybridNoticeRanker.Features>();
        corpus.add(query);
        for (int id = 1; id <= 45; id++) {
            var item = new HybridNoticeRanker.Candidate(id, .80, Set.of("반도체", "장비" + id),
                    Set.of("공정", "결함탐지"), List.of("반도체", "공정", "결함탐지"));
            candidates.add(item);
            corpus.add(item.features());
        }
        var expected = HybridNoticeRanker.rank(query.title, query.purpose, candidates);
        java.util.Collections.reverse(candidates);
        var actual = HybridNoticeRanker.rank(query, candidates, HybridNoticeRanker.Statistics.of(corpus).excluding(query));
        assertThat(actual).isEqualTo(expected);
        assertThat(actual).hasSize(20);
        assertThat(actual.getFirst().id()).isEqualTo(1);
    }

}
