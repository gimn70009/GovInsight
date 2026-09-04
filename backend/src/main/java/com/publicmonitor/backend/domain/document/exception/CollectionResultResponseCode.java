package com.publicmonitor.backend.domain.document.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CollectionResultResponseCode implements BaseResponseCode {

    RUN_NOT_FOUND("COLLECTION_RESULT_404_1", HttpStatus.NOT_FOUND.value(), "모니터링 실행을 찾을 수 없습니다."),
    JOB_ID_MISMATCH("COLLECTION_RESULT_409_1", HttpStatus.CONFLICT.value(), "Python 작업 ID가 일치하지 않습니다."),
    SOURCE_NOT_INCLUDED("COLLECTION_RESULT_409_2", HttpStatus.CONFLICT.value(), "실행에 포함되지 않은 소스입니다.");

    private final String code;
    private final int httpStatus;
    private final String message;
}
