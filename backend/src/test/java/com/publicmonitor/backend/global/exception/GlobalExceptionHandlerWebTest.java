package com.publicmonitor.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerWebTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 실제_JSON_응답_전송_중_연결이_끊기면_응답을_다시_쓰지_않는다(boolean committed) throws Exception {
        AtomicInteger writeAttempts = new AtomicInteger();
        AtomicInteger statusChangesAfterDisconnect = new AtomicInteger();
        IOException socketFailure = new IOException("현재 연결은 사용자의 호스트 시스템의 소프트웨어의 의해 중단되었습니다");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController(null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter((request, response, chain) -> {
                    ServletOutputStream disconnectedStream = new ServletOutputStream() {
                        @Override
                        public void write(int value) throws IOException {
                            writeAttempts.incrementAndGet();
                            throw new ClientAbortException(socketFailure);
                        }

                        @Override
                        public void write(byte[] bytes, int offset, int length) throws IOException {
                            writeAttempts.incrementAndGet();
                            throw new ClientAbortException(socketFailure);
                        }

                        @Override
                        public boolean isReady() { return true; }

                        @Override
                        public void setWriteListener(WriteListener listener) { }
                    };
                    chain.doFilter(request, new HttpServletResponseWrapper((HttpServletResponse) response) {
                        @Override
                        public ServletOutputStream getOutputStream() { return disconnectedStream; }

                        @Override
                        public boolean isCommitted() {
                            return (committed && writeAttempts.get() > 0) || super.isCommitted();
                        }

                        @Override
                        public void setStatus(int status) {
                            if (writeAttempts.get() > 0) statusChangesAfterDisconnect.incrementAndGet();
                            super.setStatus(status);
                        }
                    });
                }).build();

        MvcResult result = mvc.perform(get("/test/json").accept(MediaType.APPLICATION_JSON)).andReturn();

        assertThat(result.getResolvedException()).isInstanceOf(HttpMessageNotWritableException.class)
                .hasRootCause(socketFailure);
        assertThat(writeAttempts.get()).isEqualTo(1);
        assertThat(statusChangesAfterDisconnect.get()).isZero();
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    }

    @Test
    void 직렬화_오류는_MVC에서도_안전한_500_응답을_반환한다() throws Exception {
        MockMvc mvc = mvc(new HttpMessageNotWritableException("private serialization detail"));

        mvc.perform(get("/test/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL_500"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
    }

    @Test
    void 없는_리소스는_기존_404_응답을_유지한다() throws Exception {
        MockMvc mvc = mvc(new NoResourceFoundException(HttpMethod.GET, "/missing.ico", "missing.ico"));

        mvc.perform(get("/test/failure"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GLOBAL_404"));
    }

    private MockMvc mvc(Exception failure) {
        return MockMvcBuilders.standaloneSetup(new TestController(failure))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @RestController
    static class TestController {
        private final Exception failure;

        TestController(Exception failure) { this.failure = failure; }

        @GetMapping("/test/json")
        Map<String, String> json() { return Map.of("message", "응답 데이터".repeat(2000)); }

        @GetMapping("/test/failure")
        Map<String, String> fail() throws Exception { throw failure; }
    }
}
