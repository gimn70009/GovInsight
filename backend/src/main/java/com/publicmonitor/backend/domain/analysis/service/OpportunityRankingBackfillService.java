package com.publicmonitor.backend.domain.analysis.service;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpportunityRankingBackfillService {
    static final int BATCH_SIZE = 200;
    private final DocumentAnalysisRepository analyses;
    private final OpportunityRankingCalculator calculator;
    private final OpportunityRankingBackfillWriter writer;

    public record Batch(long lastId, int examined, int updated) {}

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Batch backfillAfter(long afterId) {
        var rows = analyses.findRankingsForBackfill(afterId, DocumentAnalysis.OPPORTUNITY_RANKING_VERSION,
                PageRequest.of(0, BATCH_SIZE));
        long lastId = afterId;
        int updated = 0;
        var malformedIds = new ArrayList<Long>();
        for (var row : rows) {
            var ranking = calculator.readStored(row.getOpportunityScore(), row.getOpportunityAssessment());
            int changed = writer.write(row.getId(), ranking);
            updated += changed;
            if (changed > 0 && ranking.malformed()) malformedIds.add(row.getId());
            lastId = row.getId();
        }
        if (!malformedIds.isEmpty()) {
            log.warn("기회 우선순위를 분류하지 못한 저장 분석. count={} analysisIds={}",
                    malformedIds.size(), malformedIds);
        }
        return new Batch(lastId, rows.size(), updated);
    }
}
