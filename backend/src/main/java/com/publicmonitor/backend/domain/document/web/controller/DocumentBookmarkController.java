package com.publicmonitor.backend.domain.document.web.controller;

import com.publicmonitor.backend.domain.document.service.DocumentBookmarkService;
import com.publicmonitor.backend.domain.document.service.DocumentDetectionSort;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionSummaryResponse;
import com.publicmonitor.backend.global.response.PageResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import com.publicmonitor.backend.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = com.publicmonitor.backend.global.config.OpenApiConfig.BEARER_AUTH)
@RestController
@Validated
@RequestMapping("/api/bookmarks")
public class DocumentBookmarkController {
    private final DocumentBookmarkService service;
    public DocumentBookmarkController(@Lazy DocumentBookmarkService service) { this.service = service; }

    @Operation(summary = "내 북마크 문서 ID 목록")
    @GetMapping
    public SuccessResponse<List<Long>> ids(@AuthenticationPrincipal CustomUserDetails principal) {
        return SuccessResponse.ok(service.ids(principal.user().getId()));
    }

    @Operation(summary = "게시글 북마크 저장", description = "로그인 계정에 문서 단위로 저장하며 반복 요청은 멱등입니다.")
    @PutMapping("/{documentId}")
    public SuccessResponse<Void> save(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Min(1) Long documentId) {
        service.set(principal.user().getId(), documentId, true);
        return SuccessResponse.empty();
    }

    @Operation(summary = "게시글 북마크 해제")
    @DeleteMapping("/{documentId}")
    public SuccessResponse<Void> remove(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Min(1) Long documentId) {
        service.set(principal.user().getId(), documentId, false);
        return SuccessResponse.empty();
    }

    @Operation(summary = "저장한 게시글 조회", description = "전체 실행에서 문서별 최신 감지 결과를 한 번씩 조회합니다.")
    @GetMapping("/documents")
    public SuccessResponse<PageResponse<DocumentDetectionSummaryResponse>> documents(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "LATEST") DocumentDetectionSort sort) {
        return SuccessResponse.ok(service.findAll(principal.user().getId(), page, size, from, to, sort));
    }
}
