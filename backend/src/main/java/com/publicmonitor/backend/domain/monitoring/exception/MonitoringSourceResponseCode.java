package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MonitoringSourceResponseCode implements BaseResponseCode {

    NOT_FOUND("MONITORING_SOURCE_404_1", HttpStatus.NOT_FOUND.value(), "모니터링 소스를 찾을 수 없습니다."),
    DUPLICATE_LIST_URL("MONITORING_SOURCE_409_1", HttpStatus.CONFLICT.value(), "이미 등록된 목록 URL입니다.");

    private final String code;
    private final int httpStatus;
    private final String message;
}
