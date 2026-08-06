package com.publicmonitor.backend.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.global.response.code.ErrorResponseCode;
import org.junit.jupiter.api.Test;

class CommonResponseTest {

    @Test
    void 성공_응답을_생성한다() {
        SuccessResponse<String> response = SuccessResponse.ok("result");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCode()).isEqualTo("SUCCESS_200");
        assertThat(response.getHttpStatus()).isEqualTo(200);
        assertThat(response.getData()).isEqualTo("result");
        assertThat(response.getTimestamp()).isNotNull();
    }

    @Test
    void 오류_응답을_생성한다() {
        ErrorResponse<Void> response = ErrorResponse.of(ErrorResponseCode.DUPLICATE_RESOURCE);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo("GLOBAL_409");
        assertThat(response.getHttpStatus()).isEqualTo(409);
        assertThat(response.getData()).isNull();
    }
}
