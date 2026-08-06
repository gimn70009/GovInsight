package com.publicmonitor.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.global.response.ErrorResponse;
import com.publicmonitor.backend.global.response.code.ErrorResponseCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @Test
    void 비즈니스_예외를_공통_오류_응답으로_변환한다() {
        BaseException exception = new BaseException(ErrorResponseCode.DUPLICATE_RESOURCE);

        ResponseEntity<ErrorResponse<Void>> result = exceptionHandler.handleBaseException(exception);

        assertThat(result.getStatusCode().value()).isEqualTo(409);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().isSuccess()).isFalse();
        assertThat(result.getBody().getCode()).isEqualTo("GLOBAL_409");
    }

    @Test
    void 예상하지_못한_예외의_내부_메시지를_노출하지_않는다() {
        ResponseEntity<ErrorResponse<Void>> result = exceptionHandler.handleUnexpectedException(
                new RuntimeException("데이터베이스 비밀번호가 포함된 내부 오류")
        );

        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getMessage()).isEqualTo("서버 내부 오류가 발생했습니다.");
    }
}
