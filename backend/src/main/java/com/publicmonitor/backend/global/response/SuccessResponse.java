package com.publicmonitor.backend.global.response;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import com.publicmonitor.backend.global.response.code.SuccessResponseCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@JsonPropertyOrder({"isSuccess", "timestamp", "code", "httpStatus", "message", "data"})
public final class SuccessResponse<T> extends BaseResponse {

    @Schema(description = "API별 성공 응답 데이터")
    private final T data;

    private SuccessResponse(T data, BaseResponseCode responseCode) {
        super(true, responseCode.getCode(), responseCode.getHttpStatus(), responseCode.getMessage());
        this.data = data;
    }

    public static <T> SuccessResponse<T> ok(T data) {
        return new SuccessResponse<>(data, SuccessResponseCode.SUCCESS_OK);
    }

    public static <T> SuccessResponse<T> created(T data) {
        return new SuccessResponse<>(data, SuccessResponseCode.SUCCESS_CREATED);
    }

    public static SuccessResponse<Void> empty() {
        return new SuccessResponse<>(null, SuccessResponseCode.SUCCESS_OK);
    }

    public static <T> SuccessResponse<T> of(T data, BaseResponseCode responseCode) {
        return new SuccessResponse<>(data, responseCode);
    }
}
