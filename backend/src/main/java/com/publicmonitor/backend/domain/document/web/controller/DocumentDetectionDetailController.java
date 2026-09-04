package com.publicmonitor.backend.domain.document.web.controller;

import com.publicmonitor.backend.domain.document.service.DocumentDetectionDetailService;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse;
import com.publicmonitor.backend.global.config.OpenApiConfig;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Lazy;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Document Detections", description = "감지 문서 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@Validated
@RequestMapping("/api/document-detections")
public class DocumentDetectionDetailController {

    private final DocumentDetectionDetailService detailService;

    public DocumentDetectionDetailController(@Lazy DocumentDetectionDetailService detailService) {
        this.detailService = detailService;
    }

    @Operation(summary = "감지 문서 상세 조회", description = "문서 메타데이터, AI 분석과 첨부파일을 구조화하여 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "감지 문서 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증 필요"),
            @ApiResponse(responseCode = "404", description = "감지 문서를 찾을 수 없음")
    })
    @GetMapping("/{detectionId}")
    public SuccessResponse<DocumentDetectionDetailResponse> findById(
            @PathVariable @Positive Long detectionId
    ) {
        return SuccessResponse.ok(detailService.findById(detectionId));
    }
}
