package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MonitoringRunResponseCode implements BaseResponseCode {

    NO_ACTIVE_SOURCE(
            "MONITORING_RUN_409_1",
            HttpStatus.CONFLICT.value(),
            "활성화된 모니터링 소스가 없습니다."
    );

    private final String code;
    private final int httpStatus;
    private final String message;
}
