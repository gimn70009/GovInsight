package com.publicmonitor.backend.global.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;

@Getter
public class BaseException extends RuntimeException {

    private final BaseResponseCode responseCode;

    public BaseException(BaseResponseCode responseCode) {
        super(responseCode.getMessage());
        this.responseCode = responseCode;
    }

    public BaseException(BaseResponseCode responseCode, String message) {
        super(message);
        this.responseCode = responseCode;
    }
}
