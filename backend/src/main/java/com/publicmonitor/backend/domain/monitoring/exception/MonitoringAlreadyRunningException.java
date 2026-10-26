package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class MonitoringAlreadyRunningException extends BaseException {
    public MonitoringAlreadyRunningException() {
        super(MonitoringRunResponseCode.ALREADY_RUNNING);
    }
}
