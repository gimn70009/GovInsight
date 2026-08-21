package com.publicmonitor.backend.domain.report.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReportResponseCode implements BaseResponseCode {

    RUN_NOT_FOUND("REPORT_404_1", HttpStatus.NOT_FOUND.value(), "모니터링 실행을 찾을 수 없습니다."),
    REPORT_NOT_FOUND("REPORT_404_2", HttpStatus.NOT_FOUND.value(), "모니터링 보고서를 찾을 수 없습니다."),
    INVALID_RUN_STATUS("REPORT_409_1", HttpStatus.CONFLICT.value(), "보고서를 처리할 수 없는 실행 상태입니다."),
    INVALID_REPORT_STATUS("REPORT_409_2", HttpStatus.CONFLICT.value(), "보고서를 처리할 수 없는 보고서 상태입니다."),
    ANALYSIS_NOT_FOUND("REPORT_409_3", HttpStatus.CONFLICT.value(), "보고서에 사용할 문서 분석 결과가 없습니다."),
    INVALID_RESULT("REPORT_400_1", HttpStatus.BAD_REQUEST.value(), "보고서 생성 결과가 올바르지 않습니다.");

    private final String code;
    private final int httpStatus;
    private final String message;
}
