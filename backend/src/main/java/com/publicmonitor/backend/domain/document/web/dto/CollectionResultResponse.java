package com.publicmonitor.backend.domain.document.web.dto;

import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "문서 수집 결과 저장 응답")
public record CollectionResultResponse(
        @Schema(description = "저장 및 변경 감지가 끝난 문서 목록")
        List<DocumentResult> documents
) {

    @Schema(description = "문서별 저장 및 변경 감지 결과")
    public record DocumentResult(
            @Schema(description = "게시글 원문 URL") String originalUrl,
            @Schema(description = "저장된 문서 식별자", example = "1") Long documentId,
            @Schema(description = "현재 문서 버전 식별자", example = "1") Long versionId,
            @Schema(description = "문서 변경 유형", example = "NEW_DOCUMENT") DocumentChangeType changeType,
            @Schema(description = "AI 분석 필요 여부", example = "true") boolean analysisRequired
    ) {
    }
}
