package com.publicmonitor.backend.domain.telegram.exception;

import com.publicmonitor.backend.global.response.code.BaseResponseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum TelegramResponseCode implements BaseResponseCode {
    DUPLICATE_RECIPIENT("TELEGRAM_400_2", HttpStatus.BAD_REQUEST.value(), "같은 채팅 ID는 한 번만 등록할 수 있습니다."),
    REPORT_NOT_FOUND("TELEGRAM_404_1", HttpStatus.NOT_FOUND.value(), "보고서를 찾을 수 없습니다."),
    SETTINGS_CHANGED("TELEGRAM_409_1", HttpStatus.CONFLICT.value(), "다른 곳에서 설정이 변경됐습니다. 새로 불러온 후 저장해 주세요."),
    REPORT_CHANGED("TELEGRAM_409_2", HttpStatus.CONFLICT.value(), "이미 처리됐거나 재전송할 수 없는 보고서입니다. 발송 내역을 새로 확인해 주세요."),
    RECIPIENT_CHANGED("TELEGRAM_409_3", HttpStatus.CONFLICT.value(), "수신 대상이 변경됐습니다. 현재 대상을 확인한 뒤 다시 시도해 주세요."),
    NOT_CONFIGURED("TELEGRAM_422_1", HttpStatus.UNPROCESSABLE_ENTITY.value(), "봇 토큰과 수신 채팅을 먼저 설정해 주세요."),
    DISABLED("TELEGRAM_422_2", HttpStatus.UNPROCESSABLE_ENTITY.value(), "보고서 발송이 꺼져 있습니다. 발송을 켠 뒤 재전송해 주세요."),
    INVALID_DATE_RANGE("TELEGRAM_400_1", HttpStatus.BAD_REQUEST.value(), "시작 날짜는 종료 날짜보다 늦을 수 없습니다.");

    private final String code;
    private final int httpStatus;
    private final String message;
}
