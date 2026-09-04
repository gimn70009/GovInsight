package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class MonitoringJobAcceptanceException extends BaseException {

    public MonitoringJobAcceptanceException() {
        super(MonitoringRunResponseCode.PYTHON_JOB_ACCEPTANCE_FAILED);
    }
}
