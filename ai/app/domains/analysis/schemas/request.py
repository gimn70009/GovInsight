from datetime import datetime
from enum import StrEnum

from pydantic import Field, model_validator

from app.core.schemas import CamelCaseModel


class AnalysisChangeType(StrEnum):
    NEW_DOCUMENT = "NEW_DOCUMENT"
    UPDATED_DOCUMENT = "UPDATED_DOCUMENT"
    UNCHANGED_DOCUMENT = "UNCHANGED_DOCUMENT"


class AnalysisAttachmentRequest(CamelCaseModel):
    attachment_id: int = Field(gt=0)
    file_name: str = Field(min_length=1, max_length=500)
    extracted_text: str | None = None


class PreviousVersionRequest(CamelCaseModel):
    version_id: int = Field(gt=0)
    title: str = Field(min_length=1, max_length=500)
    content_text: str | None = None


class AnalysisDocumentRequest(CamelCaseModel):
    detection_id: int = Field(gt=0)
    document_id: int = Field(gt=0)
    version_id: int = Field(gt=0)
    change_type: AnalysisChangeType
    organization_name: str = Field(min_length=1, max_length=100)
    board_name: str = Field(min_length=1, max_length=100)
    title: str = Field(min_length=1, max_length=500)
    content_text: str | None = None
    published_at: datetime | None = None
    original_url: str = Field(min_length=1, max_length=2000)
    attachments: list[AnalysisAttachmentRequest] = Field(default_factory=list)
    previous_version: PreviousVersionRequest | None = None

    @model_validator(mode="after")
    def require_analyzable_text(self) -> "AnalysisDocumentRequest":
        has_content = bool(self.content_text and self.content_text.strip())
        has_attachment_text = any(
            attachment.extracted_text and attachment.extracted_text.strip()
            for attachment in self.attachments
        )
        if not has_content and not has_attachment_text:
            raise ValueError("게시글 본문 또는 첨부파일 추출 텍스트가 필요합니다.")
        return self


class AnalysisJobRequest(CamelCaseModel):
    run_id: int = Field(gt=0)
    documents: list[AnalysisDocumentRequest] = Field(min_length=1)
