package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class MonitoringSchedulePendingException extends BaseException {
    public MonitoringSchedulePendingException() {
        super(MonitoringRunResponseCode.SCHEDULE_PENDING);
    }
}
