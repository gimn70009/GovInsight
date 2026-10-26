package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisTask;
import com.publicmonitor.backend.domain.document.entity.DocumentAnalysisStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunStatus;

final class DocumentAnalysisStatusResolver {
    private DocumentAnalysisStatusResolver() {}

    static boolean isProcessing(MonitoringRunStatus runStatus, AnalysisTask.State taskState) {
        return runStatus != null && MonitoringRunStatus.inProgress().contains(runStatus)
                && taskState != AnalysisTask.State.DONE && taskState != AnalysisTask.State.FAILED;
    }

    static DocumentAnalysisStatus resolve(boolean hasAnalysis, MonitoringRunStatus runStatus,
                                          AnalysisTask.State taskState) {
        if (hasAnalysis) return DocumentAnalysisStatus.COMPLETED;
        // ANALYZED means all analysis callbacks have been stored; only proposals remain.
        return isProcessing(runStatus, taskState) && taskState != AnalysisTask.State.ANALYZED
                ? DocumentAnalysisStatus.PENDING : DocumentAnalysisStatus.FAILED;
    }
}
