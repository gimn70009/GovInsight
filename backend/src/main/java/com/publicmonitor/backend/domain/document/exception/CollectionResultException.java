package com.publicmonitor.backend.domain.document.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class CollectionResultException extends BaseException {

    public CollectionResultException(CollectionResultResponseCode responseCode) {
        super(responseCode);
    }
}
