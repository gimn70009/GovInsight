package com.publicmonitor.backend.domain.document.web.dto;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Python 문서 수집 결과 전달 요청")
public record CollectionResultRequest(
        @Schema(description = "Spring Boot에서 생성한 실행 식별자", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Positive Long runId,

        @Schema(description = "Python이 발급한 작업 식별자", example = "24c482f0-c36f-4ff9-80f2-1689819b65d3", maxLength = 100, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String jobId,

        @Schema(description = "소스별 수집 결과", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<@Valid SourceResult> sources
) {

    @Schema(description = "모니터링 소스 하나의 수집 결과")
    public record SourceResult(
            @Schema(description = "모니터링 소스 식별자", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull @Positive Long sourceId,

            @Schema(description = "소스 수집 상태", example = "COMPLETED", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull CollectionSourceStatus status,

            @Schema(description = "소스 수집 실패 원인", maxLength = 2000, nullable = true)
            @Size(max = 2000) String errorMessage,

            @Schema(description = "해당 소스에서 수집한 문서 목록", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull List<@Valid CollectedDocument> documents
    ) {
    }

    @Schema(description = "수집한 게시글 정보")
    public record CollectedDocument(
            @Schema(description = "게시글 원문 URL", example = "https://example.go.kr/board/view.do?id=100", maxLength = 2000, requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 2000) String originalUrl,

            @Schema(description = "원본 사이트의 게시글 식별자", example = "100", maxLength = 200, nullable = true)
            @Size(max = 200) String externalDocumentId,

            @Schema(description = "게시글 제목", example = "2026년 기업 지원사업 공고", maxLength = 500, requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 500) String title,

            @Schema(description = "정리된 게시글 본문", nullable = true)
            String contentText,

            @Schema(description = "원문 게시일", example = "2026-08-20T09:00:00", nullable = true)
            LocalDateTime publishedAt,

            @Schema(description = "수집한 첨부파일 목록", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull List<@Valid CollectedAttachment> attachments
    ) {
    }

    @Schema(description = "수집한 첨부파일과 파싱 결과")
    public record CollectedAttachment(
            @Schema(description = "정리된 첨부파일 이름", example = "사업공고.hwp", maxLength = 500, requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 500) String fileName,

            @Schema(description = "첨부파일 다운로드 URL", example = "https://example.go.kr/files/100", maxLength = 2000, requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 2000) String downloadUrl,

            @Schema(description = "첨부파일 MIME 타입", example = "application/pdf", maxLength = 200, nullable = true)
            @Size(max = 200) String contentType,

            @Schema(description = "첨부파일 크기(Byte)", example = "102400", minimum = "0", nullable = true)
            @PositiveOrZero Long fileSize,

            @Schema(description = "첨부파일 SHA-256 해시", example = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", pattern = "[0-9a-f]{64}", nullable = true)
            @Pattern(regexp = "[0-9a-f]{64}") String fileHash,

            @Schema(description = "PDF·HWP·HWPX에서 추출한 텍스트", nullable = true)
            String extractedText,

            @Schema(description = "첨부파일 텍스트 파싱 상태", example = "COMPLETED", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull AttachmentParseStatus parseStatus,

            @Schema(description = "다운로드 또는 파싱 실패 원인", maxLength = 2000, nullable = true)
            @Size(max = 2000) String errorMessage
    ) {
        public CollectedAttachment(
                String fileName,
                String downloadUrl,
                String contentType,
                Long fileSize,
                String fileHash,
                AttachmentParseStatus parseStatus,
                String errorMessage
        ) {
            this(fileName, downloadUrl, contentType, fileSize, fileHash, null, parseStatus, errorMessage);
        }

        public CollectedAttachment(String fileName, String downloadUrl) {
            this(fileName, downloadUrl, null, null, null, null, AttachmentParseStatus.PENDING, null);
        }
    }
}
