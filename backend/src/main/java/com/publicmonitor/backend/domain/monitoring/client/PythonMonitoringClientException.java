package com.publicmonitor.backend.domain.monitoring.client;

public class PythonMonitoringClientException extends RuntimeException {

    public PythonMonitoringClientException(String message) {
        super(message);
    }

    public PythonMonitoringClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
