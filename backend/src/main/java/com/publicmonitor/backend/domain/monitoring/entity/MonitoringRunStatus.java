package com.publicmonitor.backend.domain.monitoring.entity;

public enum MonitoringRunStatus {
    REQUESTED,
    ACCEPTED,
    RUNNING,
    COLLECTED,
    COMPLETED,
    FAILED;

    public static java.util.Set<MonitoringRunStatus> inProgress() {
        return java.util.Set.of(REQUESTED, ACCEPTED, RUNNING, COLLECTED);
    }
}
