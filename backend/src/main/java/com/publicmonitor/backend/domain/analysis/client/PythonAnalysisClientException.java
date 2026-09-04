package com.publicmonitor.backend.domain.analysis.client;

public class PythonAnalysisClientException extends RuntimeException {

    public PythonAnalysisClientException(String message) {
        super(message);
    }

    public PythonAnalysisClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
