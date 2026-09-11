package com.publicmonitor.backend.domain.document.web.controller;

import com.publicmonitor.backend.domain.document.service.LegalPairService;
import com.publicmonitor.backend.domain.document.web.dto.LegalPairResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/document-detections")
public class LegalPairController {
    private final LegalPairService service;

    public LegalPairController(@org.springframework.context.annotation.Lazy LegalPairService service) {
        this.service = service;
    }

    @Operation(summary = "선택한 유사 공고와 법률 조건 함께 검토", description = "확인된 조항을 함께 해석하며 동일 입력 결과는 재사용합니다.")
    @PostMapping("/{currentId}/similar-notices/{similarId}/legal-review")
    public SuccessResponse<LegalPairResponse> compare(@PathVariable @Min(1) Long currentId,
            @PathVariable @Min(1) Long similarId) {
        return SuccessResponse.ok(service.compare(currentId, similarId));
    }
}
