package com.publicmonitor.backend.domain.analysis.event;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.publicmonitor.backend.domain.analysis.client.PythonAnalysisClient;
import com.publicmonitor.backend.domain.analysis.service.AnalysisJobRequestService;
import com.publicmonitor.backend.domain.report.service.ReportJobRequestService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalysisJobEventListenerTest {

    @Mock
    private AnalysisJobRequestService analysisJobRequestService;

    @Mock
    private PythonAnalysisClient pythonAnalysisClient;

    @Mock
    private ReportJobRequestService reportJobRequestService;

    @Test
    void 분석_대상이_없으면_기존_분석으로_보고서_생성을_요청한다() {
        given(analysisJobRequestService.prepare(2L)).willReturn(Optional.empty());
        AnalysisJobEventListener listener = new AnalysisJobEventListener(
                analysisJobRequestService, pythonAnalysisClient, reportJobRequestService
        );

        listener.requestAnalysis(new CollectionStoredEvent(2L));

        verify(reportJobRequestService).request(2L);
        verifyNoInteractions(pythonAnalysisClient);
    }
}
