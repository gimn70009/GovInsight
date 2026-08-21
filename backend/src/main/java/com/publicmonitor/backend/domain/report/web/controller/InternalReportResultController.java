package com.publicmonitor.backend.domain.report.web.controller;

import com.publicmonitor.backend.domain.report.service.ReportResultService;
import com.publicmonitor.backend.domain.report.web.dto.ReportResultRequest;
import com.publicmonitor.backend.domain.report.web.dto.ReportResultResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal APIs", description = "Spring Boot와 Python 사이에서만 사용하는 내부 통신 API")
@RestController
@RequestMapping("/internal/monitoring/report-results")
public class InternalReportResultController {

    private final ReportResultService reportResultService;

    public InternalReportResultController(@Lazy ReportResultService reportResultService) {
        this.reportResultService = reportResultService;
    }

    @Operation(
            summary = "모니터링 실행 보고서 결과 수신",
            description = "Python이 생성한 실행 보고서 제목과 요약을 저장합니다. 외부 사용자용 API가 아닙니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "보고서 결과 저장 성공"),
            @ApiResponse(responseCode = "400", description = "요청값 검증 실패"),
            @ApiResponse(responseCode = "404", description = "실행 또는 보고서를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "실행 또는 보고서 상태 불일치")
    })
    @PostMapping
    public SuccessResponse<ReportResultResponse> receive(
            @Valid @RequestBody ReportResultRequest request
    ) {
        return SuccessResponse.ok(reportResultService.receive(request));
    }
}
