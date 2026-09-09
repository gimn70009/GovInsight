package com.publicmonitor.backend.domain.telegram.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class TelegramException extends BaseException {
    public TelegramException(TelegramResponseCode code) {
        super(code);
    }
}
