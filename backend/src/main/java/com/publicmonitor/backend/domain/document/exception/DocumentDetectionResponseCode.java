package com.publicmonitor.backend.domain.document.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DocumentDetectionResponseCode implements BaseResponseCode {

    INVALID_DATE_RANGE(
            "DOCUMENT_DETECTION_400_1",
            HttpStatus.BAD_REQUEST.value(),
            "조회 시작 일시는 종료 일시보다 늦을 수 없습니다."
    ),
    PROPOSAL_SOURCE_NOT_FOUND("DOCUMENT_DETECTION_404_2", HttpStatus.NOT_FOUND.value(),
            "이 문서 버전에 속한 첨부 양식을 찾을 수 없습니다."),
    PROPOSAL_SOURCE_UNAVAILABLE("DOCUMENT_DETECTION_400_2", HttpStatus.BAD_REQUEST.value(),
            "선택한 첨부파일의 본문을 초안 작성에 사용할 수 없습니다."),
    PROPOSAL_DRAFT_NOT_FOUND("DOCUMENT_DETECTION_404_3", HttpStatus.NOT_FOUND.value(),
            "저장된 초안을 찾을 수 없습니다."),
    PROPOSAL_DRAFT_CONFLICT("DOCUMENT_DETECTION_409_1", HttpStatus.CONFLICT.value(),
            "초안이 다른 요청에서 변경되었거나 처리 중입니다. 최신 초안을 확인한 뒤 다시 시도해 주세요."),
    PROPOSAL_PREVIOUS_NOT_FOUND("DOCUMENT_DETECTION_404_4", HttpStatus.NOT_FOUND.value(),
            "복원할 이전 초안이 없습니다."),
    NOT_FOUND("DOCUMENT_DETECTION_404_1", HttpStatus.NOT_FOUND.value(), "감지 문서를 찾을 수 없습니다.");

    private final String code;
    private final int httpStatus;
    private final String message;
}
