package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class MonitoringScheduleChangedException extends BaseException {
    public MonitoringScheduleChangedException() {
        super(MonitoringScheduleResponseCode.PENDING_CHANGED);
    }
}
