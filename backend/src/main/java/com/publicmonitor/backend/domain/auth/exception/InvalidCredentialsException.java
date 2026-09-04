package com.publicmonitor.backend.domain.auth.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class InvalidCredentialsException extends BaseException {

    public InvalidCredentialsException() {
        super(AuthResponseCode.INVALID_CREDENTIALS);
    }
}
