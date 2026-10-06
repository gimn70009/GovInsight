package com.publicmonitor.backend.domain.analysis.web.controller;

import com.publicmonitor.backend.domain.analysis.service.AnalysisResultService;
import com.publicmonitor.backend.domain.analysis.web.dto.ProposalResultRequest;
import com.publicmonitor.backend.domain.analysis.web.dto.ProposalResultResponse;
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
@RequestMapping("/internal/monitoring/proposal-results")
public class InternalProposalResultController {

    private final AnalysisResultService analysisResultService;

    @Operation(
            summary = "사업 제안 후속 결과 수신",
            description = "먼저 저장된 공고 분석에 비동기로 생성된 사업 제안 결과만 갱신합니다. 현재 분석 시도의 jobId와 분석 저장 완료 단계를 확인하며, 만료되거나 이전 시도인 경우 409를 반환합니다. 완료된 동일 시도의 재전송은 갱신·발송을 반복하지 않습니다."
    )
    @PostMapping
    public SuccessResponse<ProposalResultResponse> receive(
            @Valid @RequestBody ProposalResultRequest request
    ) {
        return SuccessResponse.ok(analysisResultService.receiveProposal(request));
    }
}
