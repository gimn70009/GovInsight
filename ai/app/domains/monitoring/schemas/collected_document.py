from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, Field


class AttachmentParseStatus(StrEnum):
    PENDING = "PENDING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    UNSUPPORTED = "UNSUPPORTED"


class CollectedAttachment(BaseModel):
    file_name: str = Field(min_length=1, max_length=500)
    download_url: str = Field(min_length=1, max_length=2000)
    content_type: str | None = Field(default=None, max_length=200)
    file_size: int | None = Field(default=None, ge=0)
    file_hash: str | None = Field(default=None, min_length=64, max_length=64)
    extracted_text: str | None = None
    parse_status: AttachmentParseStatus = AttachmentParseStatus.PENDING
    error_message: str | None = Field(default=None, max_length=2000)


class CollectedDocument(BaseModel):
    original_url: str = Field(min_length=1, max_length=2000)
    external_document_id: str | None = Field(default=None, max_length=200)
    title: str = Field(min_length=1, max_length=500)
    content_text: str | None = None
    published_at: datetime | None = None
    attachments: list[CollectedAttachment] = Field(default_factory=list)


class SourceCollectionResult(BaseModel):
    source_id: int = Field(gt=0)
    documents: list[CollectedDocument] = Field(default_factory=list)
    error_message: str | None = Field(default=None, max_length=2000)

    @property
    def succeeded(self) -> bool:
        return self.error_message is None