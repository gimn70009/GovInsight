package com.publicmonitor.backend.domain.analysis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpportunityRankingInitializer implements ApplicationRunner {
    private final OpportunityRankingBackfillService backfill;

    @Override
    public void run(ApplicationArguments args) {
        long afterId = 0;
        long updated = 0;
        OpportunityRankingBackfillService.Batch batch;
        do {
            batch = backfill.backfillAfter(afterId);
            afterId = batch.lastId();
            updated += batch.updated();
        } while (batch.examined() == OpportunityRankingBackfillService.BATCH_SIZE);
        if (updated > 0) log.info("저장 분석의 기회 우선순위 보완 완료. updatedCount={}", updated);
    }
}
