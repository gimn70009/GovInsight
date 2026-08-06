package com.publicmonitor.backend.global.exception;

import com.publicmonitor.backend.global.response.ErrorResponse;
import com.publicmonitor.backend.global.response.code.ErrorResponseCode;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse<Map<String, String>>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );

        ErrorResponse<Map<String, String>> response = ErrorResponse.of(
                ErrorResponseCode.INVALID_HTTP_MESSAGE_BODY,
                ErrorResponseCode.INVALID_HTTP_MESSAGE_BODY.getMessage(),
                fieldErrors
        );
        return responseEntity(response);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse<Map<String, String>>> handleBind(BindException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );

        ErrorResponse<Map<String, String>> response = ErrorResponse.of(
                ErrorResponseCode.INVALID_HTTP_MESSAGE_PARAMETER,
                ErrorResponseCode.INVALID_HTTP_MESSAGE_PARAMETER.getMessage(),
                fieldErrors
        );
        return responseEntity(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception
    ) {
        log.debug("요청 본문을 읽을 수 없습니다.", exception);
        return responseEntity(ErrorResponse.of(ErrorResponseCode.INVALID_HTTP_MESSAGE_BODY));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse<Void>> handleInvalidParameter(Exception exception) {
        log.debug("요청 파라미터가 올바르지 않습니다.", exception);
        return responseEntity(ErrorResponse.of(ErrorResponseCode.INVALID_HTTP_MESSAGE_PARAMETER));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse<Void>> handleMissingRequestPart(
            MissingServletRequestPartException exception
    ) {
        log.debug("필수 요청 파트가 누락되었습니다.", exception);
        return responseEntity(ErrorResponse.of(ErrorResponseCode.INVALID_HTTP_MESSAGE_PARAMETER));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse<Void>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception
    ) {
        log.debug("지원하지 않는 HTTP 메서드입니다.", exception);
        return responseEntity(ErrorResponse.of(ErrorResponseCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        log.debug("요청한 엔드포인트를 찾을 수 없습니다.", exception);
        return responseEntity(ErrorResponse.of(ErrorResponseCode.NOT_FOUND_ENDPOINT));
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ErrorResponse<Void>> handleBaseException(BaseException exception) {
        ErrorResponse<Void> response = ErrorResponse.of(exception.getResponseCode(), exception.getMessage());
        return responseEntity(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("처리하지 못한 서버 오류가 발생했습니다.", exception);
        return responseEntity(ErrorResponse.of(ErrorResponseCode.INTERNAL_SERVER_ERROR));
    }

    private <T> ResponseEntity<ErrorResponse<T>> responseEntity(ErrorResponse<T> response) {
        return ResponseEntity.status(response.getHttpStatus()).body(response);
    }
}
