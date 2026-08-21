package com.publicmonitor.backend.domain.analysis.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class AnalysisResultException extends BaseException {

    public AnalysisResultException(AnalysisResultResponseCode responseCode) {
        super(responseCode);
    }
}
