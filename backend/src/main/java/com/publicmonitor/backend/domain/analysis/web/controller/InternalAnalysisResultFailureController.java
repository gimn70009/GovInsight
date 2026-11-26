package com.publicmonitor.backend.domain.analysis.web.controller;

import com.publicmonitor.backend.domain.analysis.service.AnalysisTaskService;
import com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultFailureRequest;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal APIs", description = "Spring Boot와 Python 사이에서만 사용하는 내부 통신 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/monitoring/analysis-result-failures")
public class InternalAnalysisResultFailureController {
    private final AnalysisTaskService tasks;

    @Operation(summary = "분석·제안 결과 거절 통지",
            description = "현재 시도·유효 시간·처리 단계가 일치할 때만 실행을 실패 처리합니다. "
                    + "이미 저장된 결과, 이전 시도, 중복 통지 및 HTTP 429는 상태를 변경하지 않습니다.")
    @PostMapping
    public SuccessResponse<FailureResponse> receive(@Valid @RequestBody AnalysisResultFailureRequest request) {
        return SuccessResponse.ok(new FailureResponse(tasks.resultRejected(request)));
    }

    public record FailureResponse(boolean applied) {}
}
