package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class MonitoringSourceNotFoundException extends BaseException {

    public MonitoringSourceNotFoundException() {
        super(MonitoringSourceResponseCode.NOT_FOUND);
    }
}
