package com.publicmonitor.backend.global.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Getter;

@Getter
@JsonPropertyOrder({"isSuccess", "timestamp", "code", "httpStatus", "message"})
@Schema(description = "GovInsight 공통 API 응답")
public abstract class BaseResponse {

    @Schema(description = "요청 성공 여부", example = "true")
    private final boolean isSuccess;
    @Schema(description = "응답 생성 시각")
    private final OffsetDateTime timestamp;
    @Schema(description = "서비스 응답 코드", example = "SUCCESS_200")
    private final String code;
    @Schema(description = "HTTP 상태 코드", example = "200")
    private final int httpStatus;
    @Schema(description = "응답 메시지", example = "요청에 성공했습니다.")
    private final String message;

    protected BaseResponse(boolean isSuccess, String code, int httpStatus, String message) {
        this.isSuccess = isSuccess;
        this.timestamp = OffsetDateTime.now();
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    @JsonProperty("isSuccess")
    public boolean isSuccess() {
        return isSuccess;
    }
}
