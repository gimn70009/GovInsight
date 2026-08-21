package com.publicmonitor.backend.domain.report.client;

public class PythonReportClientException extends RuntimeException {

    public PythonReportClientException(String message) {
        super(message);
    }

    public PythonReportClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
