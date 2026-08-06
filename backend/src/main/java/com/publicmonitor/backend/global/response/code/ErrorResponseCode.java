package com.publicmonitor.backend.global.response.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorResponseCode implements BaseResponseCode {

    BAD_REQUEST("GLOBAL_400_1", HttpStatus.BAD_REQUEST.value(), "잘못된 요청입니다."),
    INVALID_HTTP_MESSAGE_BODY("GLOBAL_400_2", HttpStatus.BAD_REQUEST.value(), "요청 본문의 형식이 올바르지 않습니다."),
    INVALID_HTTP_MESSAGE_PARAMETER("GLOBAL_400_3", HttpStatus.BAD_REQUEST.value(), "요청 파라미터의 형식이 올바르지 않습니다."),
    ACCESS_DENIED("GLOBAL_403", HttpStatus.FORBIDDEN.value(), "해당 요청에 대한 접근 권한이 없습니다."),
    NOT_FOUND_ENDPOINT("GLOBAL_404", HttpStatus.NOT_FOUND.value(), "존재하지 않는 엔드포인트입니다."),
    METHOD_NOT_ALLOWED("GLOBAL_405", HttpStatus.METHOD_NOT_ALLOWED.value(), "지원하지 않는 HTTP 메서드입니다."),
    DUPLICATE_RESOURCE("GLOBAL_409", HttpStatus.CONFLICT.value(), "이미 존재하는 리소스입니다."),
    INTERNAL_SERVER_ERROR("GLOBAL_500", HttpStatus.INTERNAL_SERVER_ERROR.value(), "서버 내부 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String message;
}
