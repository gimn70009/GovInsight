package com.publicmonitor.backend.global.response;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@JsonPropertyOrder({"isSuccess", "timestamp", "code", "httpStatus", "message", "data"})
public final class ErrorResponse<T> extends BaseResponse {

    @Schema(description = "오류 상세 데이터", nullable = true)
    private final T data;

    private ErrorResponse(BaseResponseCode responseCode, String message, T data) {
        super(false, responseCode.getCode(), responseCode.getHttpStatus(), message);
        this.data = data;
    }

    public static ErrorResponse<Void> of(BaseResponseCode responseCode) {
        return new ErrorResponse<>(responseCode, responseCode.getMessage(), null);
    }

    public static ErrorResponse<Void> of(BaseResponseCode responseCode, String message) {
        return new ErrorResponse<>(responseCode, message, null);
    }

    public static <T> ErrorResponse<T> of(BaseResponseCode responseCode, String message, T data) {
        return new ErrorResponse<>(responseCode, message, data);
    }
}
