package com.publicmonitor.backend.domain.analysis.service;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OpportunityRankingBackfillWriter {
    private final DocumentAnalysisRepository analyses;

    // Hold one row at a time so callbacks updating several analyses cannot form a lock cycle with backfill.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int write(Long id, OpportunityRankingCalculator.Ranking ranking) {
        return analyses.backfillRanking(id, ranking.score(), ranking.priority(),
                DocumentAnalysis.OPPORTUNITY_RANKING_VERSION);
    }
}
