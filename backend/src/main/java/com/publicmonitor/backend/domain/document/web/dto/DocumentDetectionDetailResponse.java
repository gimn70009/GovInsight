package com.publicmonitor.backend.domain.document.web.dto;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisFavorability;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "감지 문서 상세")
public record DocumentDetectionDetailResponse(
        Long detectionId,
        String organizationName,
        String boardName,
        String title,
        LocalDateTime publishedAt,
        DocumentChangeType changeType,
        String originalUrl,
        LocalDateTime lastCheckedAt,
        Analysis analysis,
        List<Attachment> attachments
) {

    @Schema(description = "문서별 AI 분석", nullable = true)
    public record Analysis(
            String summary,
            List<String> keyPoints,
            DocumentImportance importance,
            String reason,
            AnalysisEligibility eligibility,
            AnalysisFavorability favorableOrNot,
            String proposalDirection
    ) {
    }

    @Schema(description = "첨부파일")
    public record Attachment(
            String fileName,
            String fileExtension,
            Long fileSize,
            AttachmentParseStatus parseStatus,
            String downloadUrl
    ) {
    }
}
