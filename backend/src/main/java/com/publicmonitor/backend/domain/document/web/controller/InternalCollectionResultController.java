package com.publicmonitor.backend.domain.document.web.controller;

import com.publicmonitor.backend.domain.document.service.CollectionResultService;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultResponse;
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
@RequestMapping("/internal/monitoring/collection-results")
public class InternalCollectionResultController {

    private final CollectionResultService collectionResultService;

    @Operation(
            summary = "문서 수집 결과 수신",
            description = "Python이 수집한 소스별 게시글과 첨부파일 결과를 받아 변경을 감지하고 Oracle에 저장합니다. 외부 사용자용 API가 아닙니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수집 결과 저장 성공"),
            @ApiResponse(responseCode = "400", description = "요청값 검증 실패"),
            @ApiResponse(responseCode = "404", description = "실행 또는 소스를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "이미 처리했거나 현재 상태에서 처리할 수 없는 결과")
    })
    @PostMapping
    public SuccessResponse<CollectionResultResponse> receive(
            @Valid @RequestBody CollectionResultRequest request
    ) {
        return SuccessResponse.ok(collectionResultService.receive(request));
    }
}
