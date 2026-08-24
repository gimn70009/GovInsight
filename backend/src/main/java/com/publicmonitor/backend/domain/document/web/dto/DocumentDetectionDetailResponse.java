package com.publicmonitor.backend.domain.document.web.dto;

import com.publicmonitor.backend.domain.analysis.entity.AnalysisEligibility;
import com.publicmonitor.backend.domain.analysis.entity.AnalysisFavorability;
import com.publicmonitor.backend.domain.analysis.entity.DocumentImportance;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityDimensionType;
import com.publicmonitor.backend.domain.analysis.entity.OpportunityPriority;
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
            Proposal proposal,
            Opportunity opportunity
    ) {
    }

    @Schema(description = "회사 활용·대응 전략")
    public record Proposal(List<Section> sections) {
    }

    @Schema(description = "회사 활용·대응 전략의 한 단계")
    public record Section(String title, String body) {
    }

    @Schema(description = "AI 기회 점수와 대응 우선순위", nullable = true)
    public record Opportunity(
            Integer totalScore,
            OpportunityPriority priority,
            List<OpportunityDimension> dimensions
    ) {
    }

    @Schema(description = "기회 점수 평가 항목")
    public record OpportunityDimension(
            OpportunityDimensionType type,
            Integer score,
            String reason
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
