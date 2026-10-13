package com.publicmonitor.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.publicmonitor.backend.global.response.ErrorResponse;
import com.publicmonitor.backend.global.response.code.ErrorResponseCode;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.stream.Stream;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import tools.jackson.core.exc.JacksonIOException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
    }

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

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getMessage()).isEqualTo("서버 내부 오류가 발생했습니다.");
    }

    @ParameterizedTest
    @MethodSource("disconnectedExceptions")
    void 연결_종료는_응답을_재전송하지_않고_DEBUG_한_줄만_남긴다(Exception exception) {
        assertThat(exceptionHandler.handleUnexpectedException(exception)).isNull();

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    static Stream<Exception> disconnectedExceptions() {
        IOException socketFailure = new IOException("현재 연결은 사용자의 호스트 시스템의 소프트웨어의 의해 중단되었습니다");
        ClientAbortException abort = new ClientAbortException(socketFailure);
        AsyncRequestNotUsableException unusable = new AsyncRequestNotUsableException(
                "ServletOutputStream failed to write", abort);
        return Stream.of(
                abort,
                unusable,
                new HttpMessageNotWritableException("Could not write JSON", JacksonIOException.construct(unusable)),
                new HttpMessageNotWritableException("Could not write JSON", abort),
                new IOException("Broken pipe"),
                new IOException("Connection reset by peer"),
                new AsyncRequestNotUsableException("Response not usable after async request completion")
        );
    }

    @ParameterizedTest
    @MethodSource("serverExceptions")
    void 실제_서버_오류는_500_응답과_ERROR_원인_로그를_유지한다(Exception exception) {
        ResponseEntity<ErrorResponse<Void>> result = exceptionHandler.handleUnexpectedException(exception);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getCode()).isEqualTo("GLOBAL_500");
        assertThat(result.getBody().getMessage()).isEqualTo("서버 내부 오류가 발생했습니다.");
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    static Stream<Exception> serverExceptions() {
        return Stream.of(
                new HttpMessageNotWritableException("Invalid JSON property", new IllegalStateException("private detail")),
                new IOException("Cannot read attachment file"),
                new SocketTimeoutException("Read timed out"),
                new ResourceAccessException("Python request failed", new IOException("Connection reset by peer")),
                new RuntimeException(new ResourceAccessException("Python request failed", new IOException("Broken pipe"))),
                new DataAccessResourceFailureException("Database unavailable", new IOException("Connection reset by peer"))
        );
    }
}
