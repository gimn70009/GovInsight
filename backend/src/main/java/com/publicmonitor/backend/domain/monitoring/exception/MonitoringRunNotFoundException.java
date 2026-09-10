package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class MonitoringRunNotFoundException extends BaseException {
    public MonitoringRunNotFoundException() { super(MonitoringRunResponseCode.NOT_FOUND); }
}
