package com.publicmonitor.backend.domain.analysis.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class OpportunityRankingInitializerTest {
    @Test
    void 각_묶음의_마지막_ID로_진행하고_짧은_묶음에서_종료한다() {
        var service = mock(OpportunityRankingBackfillService.class);
        given(service.backfillAfter(0)).willReturn(new OpportunityRankingBackfillService.Batch(250, 200, 190));
        given(service.backfillAfter(250)).willReturn(new OpportunityRankingBackfillService.Batch(510, 200, 200));
        given(service.backfillAfter(510)).willReturn(new OpportunityRankingBackfillService.Batch(514, 3, 3));

        new OpportunityRankingInitializer(service).run(new DefaultApplicationArguments());

        var order = inOrder(service);
        order.verify(service).backfillAfter(0);
        order.verify(service).backfillAfter(250);
        order.verify(service).backfillAfter(510);
        order.verifyNoMoreInteractions();
    }
}
