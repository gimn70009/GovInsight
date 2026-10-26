package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.SourceResult;
import com.publicmonitor.backend.domain.document.web.dto.CollectionSourceStatus;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunSourceRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MonitoringCollectionRecoveryService {
    private final MonitoringRunRepository runs;
    private final MonitoringRunSourceRepository sources;

    @Transactional
    public boolean expire(Long runId, LocalDateTime cutoff, LocalDateTime now) {
        var run = runs.findForUpdate(runId).orElse(null);
        if (run == null || !run.expireCollection(cutoff, now)) {
            return false;
        }
        for (var source : sources.findWithSourcesByMonitoringRunId(runId)) {
            source.fail(run.getErrorMessage(), now);
            MonitoringWarningDetails.record(source, new SourceResult(
                    source.getMonitoringSource().getId(), CollectionSourceStatus.FAILED,
                    run.getErrorMessage(), List.of()));
        }
        return true;
    }
}
