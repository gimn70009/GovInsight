package com.publicmonitor.backend.domain.document.web.controller;

import com.publicmonitor.backend.domain.document.service.DocumentDetectionQueryService;
import com.publicmonitor.backend.domain.document.service.DocumentDetectionSort;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
import com.publicmonitor.backend.global.config.OpenApiConfig;
import com.publicmonitor.backend.global.response.PageResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Lazy;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Document Detections", description = "감지 문서 조회 API")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@Validated
@RequestMapping("/api/document-detections")
public class DocumentDetectionController {

    private final DocumentDetectionQueryService queryService;

    public DocumentDetectionController(@Lazy DocumentDetectionQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "감지 문서 목록 조회", description = "최근 확인 순서 또는 기회 점수 순서로 감지 문서를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "감지 문서 조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 조회 일시 범위"),
            @ApiResponse(responseCode = "401", description = "인증 필요")
    })
    @GetMapping
    public SuccessResponse<PageResponse<DocumentDetectionSummaryResponse>> findAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Min(1) Long runId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "LATEST") DocumentDetectionSort sort
    ) {
        return SuccessResponse.ok(queryService.findAll(page, size, from, to, runId, sort));
    }
}
