package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class NoActiveMonitoringSourceException extends BaseException {

    public NoActiveMonitoringSourceException() {
        super(MonitoringRunResponseCode.NO_ACTIVE_SOURCE);
    }
}
