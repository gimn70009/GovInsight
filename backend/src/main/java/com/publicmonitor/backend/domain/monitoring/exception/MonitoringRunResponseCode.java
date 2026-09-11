package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MonitoringRunResponseCode implements BaseResponseCode {

    NOT_FOUND("MONITORING_RUN_404_1", 404, "모니터링 실행 이력을 찾을 수 없습니다."),

    NO_ACTIVE_SOURCE(
            "MONITORING_RUN_422_1",
            HttpStatus.UNPROCESSABLE_CONTENT.value(),
            "실행할 활성 모니터링 소스가 없습니다."
    ),
    PYTHON_JOB_ACCEPTANCE_FAILED(
            "MONITORING_RUN_502_1",
            HttpStatus.BAD_GATEWAY.value(),
            "Python 모니터링 작업 접수에 실패했습니다."
    );

    private final String code;
    private final int httpStatus;
    private final String message;
}
