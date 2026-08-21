package com.publicmonitor.backend.domain.report.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class ReportException extends BaseException {

    public ReportException(ReportResponseCode responseCode) {
        super(responseCode);
    }
}
