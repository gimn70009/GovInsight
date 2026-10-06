package com.publicmonitor.backend.domain.analysis.event;

import static org.mockito.Mockito.*;
import com.publicmonitor.backend.domain.analysis.service.AnalysisTaskService;
import com.publicmonitor.backend.domain.analysis.service.AnalysisTaskRunner;
import org.junit.jupiter.api.Test;

class AnalysisJobEventListenerTest {
    @Test void savesDurableTaskBeforeDispatch() {
        var tasks = mock(AnalysisTaskService.class);
        var runner = mock(AnalysisTaskRunner.class);
        var listener = new AnalysisJobEventListener(tasks, runner);
        listener.saveTask(new CollectionStoredEvent(2L));
        verify(tasks).enqueue(2L);
        verifyNoInteractions(runner);
        listener.requestAnalysis(new CollectionStoredEvent(2L));
        verify(runner).run(2L);
    }
    @Test void dispatchFailureLeavesDurableRecoveryResponsible() {
        var tasks = mock(AnalysisTaskService.class);
        var runner = mock(AnalysisTaskRunner.class);
        doThrow(new IllegalStateException()).when(runner).run(2L);
        new AnalysisJobEventListener(tasks, runner).requestAnalysis(new CollectionStoredEvent(2L));
        verifyNoInteractions(tasks);
    }
}
