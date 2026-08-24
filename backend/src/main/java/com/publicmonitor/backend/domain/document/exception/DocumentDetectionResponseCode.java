package com.publicmonitor.backend.domain.document.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DocumentDetectionResponseCode implements BaseResponseCode {

    NOT_FOUND("DOCUMENT_DETECTION_404_1", HttpStatus.NOT_FOUND.value(), "감지 문서를 찾을 수 없습니다.");

    private final String code;
    private final int httpStatus;
    private final String message;
}
