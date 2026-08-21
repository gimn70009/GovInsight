package com.publicmonitor.backend.domain.analysis.web.controller;

import com.publicmonitor.backend.domain.analysis.service.AnalysisResultService;
import com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultRequest;
import com.publicmonitor.backend.domain.analysis.web.dto.AnalysisResultResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
@RequestMapping("/internal/monitoring/analysis-results")
public class InternalAnalysisResultController {

    private final AnalysisResultService analysisResultService;

    @Operation(
            summary = "AI 문서 분석 결과 수신",
            description = "Python이 생성한 문서별 요약과 중요도 결과를 검증하여 Oracle에 저장합니다. 외부 사용자용 API가 아닙니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "분석 결과 저장 성공"),
            @ApiResponse(responseCode = "400", description = "요청값 검증 실패"),
            @ApiResponse(responseCode = "404", description = "실행 또는 문서 감지 결과를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "실행 상태 또는 문서 관계 불일치")
    })
    @PostMapping
    public SuccessResponse<AnalysisResultResponse> receive(
            @Valid @RequestBody AnalysisResultRequest request
    ) {
        return SuccessResponse.ok(analysisResultService.receive(request));
    }
}
