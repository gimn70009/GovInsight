package com.publicmonitor.backend.domain.settings.exception;
import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
@Getter @RequiredArgsConstructor
public enum EvaluationSettingsResponseCode implements BaseResponseCode {
    CHANGED("EVALUATION_SETTINGS_409_1", 409, "다른 곳에서 설정이 변경되었습니다. 최신 설정을 불러온 뒤 다시 저장해 주세요."),
    INVALID_SELECTION("EVALUATION_SETTINGS_400_1", 400, "선택 항목이 중복되었거나 허용된 목록에 없습니다."),
    UNKNOWN_REVISION("EVALUATION_SETTINGS_400_2", 400, "분석에 사용한 설정 버전을 확인할 수 없습니다.");
    private final String code;
    private final int httpStatus;
    private final String message;
}
