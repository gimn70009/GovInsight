package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class DuplicateMonitoringSourceException extends BaseException {

    public DuplicateMonitoringSourceException() {
        super(MonitoringSourceResponseCode.DUPLICATE_LIST_URL);
    }
}
