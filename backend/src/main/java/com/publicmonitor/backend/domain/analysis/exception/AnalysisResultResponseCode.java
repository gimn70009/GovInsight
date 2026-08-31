package com.publicmonitor.backend.domain.analysis.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AnalysisResultResponseCode implements BaseResponseCode {

    RUN_NOT_FOUND("ANALYSIS_RESULT_404_1", HttpStatus.NOT_FOUND.value(), "모니터링 실행을 찾을 수 없습니다."),
    DETECTION_NOT_FOUND("ANALYSIS_RESULT_404_2", HttpStatus.NOT_FOUND.value(), "문서 감지 결과를 찾을 수 없습니다."),
    ANALYSIS_NOT_FOUND("ANALYSIS_RESULT_404_3", HttpStatus.NOT_FOUND.value(), "저장된 공고 분석 결과를 찾을 수 없습니다."),
    INVALID_RUN_STATUS("ANALYSIS_RESULT_409_1", HttpStatus.CONFLICT.value(), "분석 결과를 저장할 수 없는 실행 상태입니다."),
    RESULT_RELATION_MISMATCH("ANALYSIS_RESULT_409_2", HttpStatus.CONFLICT.value(), "분석 결과의 문서 관계가 일치하지 않습니다.");

    private final String code;
    private final int httpStatus;
    private final String message;
}
