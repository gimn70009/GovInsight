package com.publicmonitor.backend.global.response.code;

public interface BaseResponseCode {

    String getCode();

    int getHttpStatus();

    String getMessage();
}
