package com.publicmonitor.backend.domain.document.web.controller;

import com.publicmonitor.backend.domain.document.service.ProposalSourceService;
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

    public ProposalWriteController(@org.springframework.context.annotation.Lazy ProposalSourceService sources,
            @org.springframework.context.annotation.Lazy ProposalWriteService writer) {
        this.sources = sources;
        this.writer = writer;
    }

    @Operation(summary = "제안서 작성에 사용할 첨부 및 ZIP 내부 문서 목록")
    @GetMapping("/proposal-sources")
    public SuccessResponse<List<ProposalSourceResponse>> sources(@PathVariable @Min(1) Long detectionId) {
        return SuccessResponse.ok(sources.list(detectionId));
    }

    @Operation(summary = "선택한 양식의 핵심 항목 최대 4개 초안 생성",
            description = "회사 프로필과 실제 양식을 사용하며 동일 입력의 완료 결과는 1시간 재사용합니다.")
    @PostMapping("/proposal-draft")
    public SuccessResponse<ProposalWriteResponse> write(@PathVariable @Min(1) Long detectionId,
            @RequestBody @Valid ProposalWriteRequest request) {
        return SuccessResponse.ok(writer.write(detectionId, request));
    }
}
