package com.publicmonitor.backend.domain.document.web.controller;

import com.publicmonitor.backend.domain.document.service.DocumentDetectionQueryService;
import com.publicmonitor.backend.domain.document.service.DocumentDetectionSort;
import com.publicmonitor.backend.domain.document.service.SimilarNoticeService;
import com.publicmonitor.backend.domain.document.web.dto.SimilarNoticeResponse;
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
import jakarta.validation.constraints.Size;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
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
    private final SimilarNoticeService similarNoticeService;

    public DocumentDetectionController(
            @Lazy DocumentDetectionQueryService queryService,
            @Lazy SimilarNoticeService similarNoticeService
    ) {
        this.queryService = queryService;
        this.similarNoticeService = similarNoticeService;
    }

    @Operation(summary = "감지 문서 목록 조회", description = "실행·기간 범위 전체에서 기관·게시판·제목(query, 최대 500자)과 우선순위(priority: HIGH/NORMAL/LOW)로 필터링한 뒤 페이지를 반환합니다. 검색은 대소문자를 구분하지 않는 일반 문자열 검색입니다.")
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
            @RequestParam(defaultValue = "LATEST") DocumentDetectionSort sort,
            @RequestParam(required = false) @Size(max = 500) String query,
            @RequestParam(required = false) OpportunityPriority priority
    ) {
        return SuccessResponse.ok(queryService.findAll(page, size, from, to, runId, sort, query, priority));
    }

    @Operation(summary = "유사 공고 비교", description = "엄격한 제목·사업 내용 기준을 통과한 유사 공고를 최대 3건 비교합니다.")
    @GetMapping("/{detectionId}/similar-notices")
    public SuccessResponse<SimilarNoticeResponse> findSimilarNotices(
            @org.springframework.web.bind.annotation.PathVariable @Min(1) Long detectionId
    ) {
        return SuccessResponse.ok(similarNoticeService.find(detectionId));
    }
}
