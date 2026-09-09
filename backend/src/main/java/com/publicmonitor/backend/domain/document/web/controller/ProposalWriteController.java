package com.publicmonitor.backend.domain.document.web.controller;

import com.publicmonitor.backend.domain.document.service.ProposalSourceService;
import com.publicmonitor.backend.domain.document.service.ProposalDraftStore;
import com.publicmonitor.backend.domain.document.web.dto.SavedProposalDraftResponse;
import com.publicmonitor.backend.domain.document.web.dto.ProposalDraftStateResponse;
import com.publicmonitor.backend.global.security.CustomUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.publicmonitor.backend.domain.document.service.ProposalWriteService;
import com.publicmonitor.backend.domain.document.web.dto.ProposalSourceResponse;
import com.publicmonitor.backend.domain.document.web.dto.ProposalWriteRequest;
import com.publicmonitor.backend.domain.document.web.dto.ProposalWriteResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/document-detections/{detectionId}")
public class ProposalWriteController {
    private final ProposalSourceService sources;
    private final ProposalWriteService writer;
    private final ProposalDraftStore drafts;

    public ProposalWriteController(@org.springframework.context.annotation.Lazy ProposalSourceService sources,
            @org.springframework.context.annotation.Lazy ProposalWriteService writer,
            @org.springframework.context.annotation.Lazy ProposalDraftStore drafts) {
        this.sources = sources;
        this.writer = writer;
        this.drafts = drafts;
    }

    @Operation(summary = "제안서 작성에 사용할 첨부 및 ZIP 내부 문서 목록")
    @GetMapping("/proposal-sources")
    public SuccessResponse<List<ProposalSourceResponse>> sources(@PathVariable @Min(1) Long detectionId) {
        return SuccessResponse.ok(sources.list(detectionId));
    }

    @Operation(summary = "선택한 양식의 핵심 항목 최대 4개 초안 생성",
            description = "완료된 초안은 계정과 첨부 양식별로 저장하며 재요청 시 저장된 결과를 반환합니다.")
    @PostMapping("/proposal-draft")
    public SuccessResponse<ProposalWriteResponse> write(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Min(1) Long detectionId,
            @RequestBody @Valid ProposalWriteRequest request) {
        return SuccessResponse.ok(writer.write(principal.user().getId(), detectionId, request));
    }

    @Operation(summary = "이 문서 버전에서 내가 작성한 초안 목록", description = "마지막으로 본 초안부터 반환하며 모델은 호출하지 않습니다.")
    @GetMapping("/proposal-drafts")
    public SuccessResponse<List<SavedProposalDraftResponse>> drafts(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Min(1) Long detectionId) {
        return SuccessResponse.ok(drafts.list(principal.user().getId(), detectionId));
    }

    @Operation(summary = "이 문서 버전의 초안 생성 상태와 저장 결과", description = "진행 중인 계정별 양식과 저장된 초안을 반환하며 모델은 호출하지 않습니다.")
    @GetMapping("/proposal-drafts/state")
    public SuccessResponse<ProposalDraftStateResponse> state(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Min(1) Long detectionId) {
        return SuccessResponse.ok(writer.state(principal.user().getId(), detectionId));
    }

    @Operation(summary = "마지막으로 본 초안 기억")
    @PutMapping("/proposal-drafts/last-viewed")
    public SuccessResponse<Void> remember(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Min(1) Long detectionId, @RequestBody @Valid ProposalWriteRequest request) {
        drafts.markViewed(principal.user().getId(), detectionId, request);
        return SuccessResponse.empty();
    }
}
