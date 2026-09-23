package com.publicmonitor.backend.domain.email.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class EmailException extends BaseException {
    public EmailException(EmailResponseCode code) {
        super(code);
    }
}
