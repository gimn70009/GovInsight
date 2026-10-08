package com.publicmonitor.backend.domain.monitoring.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MonitoringScheduleResponseCode implements BaseResponseCode {

    PENDING_CHANGED("MONITORING_SCHEDULE_409_1", 409,
            "대기 예약이 이미 시작되었거나 변경되었습니다. 현재 상태를 확인해 주세요.");

    private final String code;
    private final int httpStatus;
    private final String message;
}
