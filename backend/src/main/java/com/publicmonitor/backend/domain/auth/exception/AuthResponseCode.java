package com.publicmonitor.backend.domain.auth.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthResponseCode implements BaseResponseCode {

    INVALID_CREDENTIALS("AUTH_401_1", HttpStatus.UNAUTHORIZED.value(), "아이디 또는 비밀번호가 올바르지 않습니다.");

    private final String code;
    private final int httpStatus;
    private final String message;
}
