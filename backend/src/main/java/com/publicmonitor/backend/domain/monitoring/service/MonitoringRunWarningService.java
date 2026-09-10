package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringRunNotFoundException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunSourceRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunWarningsResponse;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunWarningsResponse.Warning;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Lazy
@RequiredArgsConstructor
public class MonitoringRunWarningService {
    private final MonitoringRunRepository runs;
    private final MonitoringRunSourceRepository sources;

    @Transactional(readOnly = true)
    public MonitoringRunWarningsResponse find(Long runId) {
        var run = runs.findById(runId).orElseThrow(MonitoringRunNotFoundException::new);
        var warnings = new ArrayList<Warning>();
        int remaining = run.getWarningCount();
        if (remaining > 0) {
            for (var source : sources.findWithSourcesByMonitoringRunId(runId)) {
                for (var warning : MonitoringWarningDetails.read(source)) {
                    if (warning.count() <= remaining) {
                        warnings.add(warning);
                        remaining -= warning.count();
                    }
                }
            }
        }
        if (remaining > 0) warnings.add(new Warning("LEGACY_DETAILS_UNAVAILABLE", null, null, null, null,
                "당시 경고의 상세 기록이 남아 있지 않아요.", remaining));
        return new MonitoringRunWarningsResponse(runId, run.getWarningCount(), warnings);
    }
}
