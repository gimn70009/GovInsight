package com.publicmonitor.backend.domain.document.exception;

import com.publicmonitor.backend.global.exception.BaseException;

public class DocumentDetectionException extends BaseException {

    public DocumentDetectionException(DocumentDetectionResponseCode responseCode) {
        super(responseCode);
    }
}
