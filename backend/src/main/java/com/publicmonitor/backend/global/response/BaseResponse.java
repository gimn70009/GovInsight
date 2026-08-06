package com.publicmonitor.backend.global.response;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.OffsetDateTime;
import lombok.Getter;

@Getter
@JsonPropertyOrder({"isSuccess", "timestamp", "code", "httpStatus", "message"})
public abstract class BaseResponse {

    private final boolean isSuccess;
    private final OffsetDateTime timestamp;
    private final String code;
    private final int httpStatus;
    private final String message;

    protected BaseResponse(boolean isSuccess, String code, int httpStatus, String message) {
        this.isSuccess = isSuccess;
        this.timestamp = OffsetDateTime.now();
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
