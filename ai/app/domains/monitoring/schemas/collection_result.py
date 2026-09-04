from enum import StrEnum
from uuid import UUID

from pydantic import Field

from app.core.schemas import CamelCaseModel
from app.domains.monitoring.schemas.collected_document import (
    AttachmentParseStatus,
    SourceCollectionResult,
)


class CollectionSourceStatus(StrEnum):
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class CollectionAttachmentRequest(CamelCaseModel):
    file_name: str = Field(min_length=1, max_length=500)
    download_url: str = Field(min_length=1, max_length=2000)
    content_type: str | None = Field(default=None, max_length=200)
    file_size: int | None = Field(default=None, ge=0)
    file_hash: str | None = Field(default=None, min_length=64, max_length=64)
    extracted_text: str | None = None
    parse_status: AttachmentParseStatus
    error_message: str | None = Field(default=None, max_length=2000)


class CollectionDocumentRequest(CamelCaseModel):
    original_url: str = Field(min_length=1, max_length=2000)
    external_document_id: str | None = Field(default=None, max_length=200)
    title: str = Field(min_length=1, max_length=500)
    content_text: str | None = None
    published_at: str | None = None
    attachments: list[CollectionAttachmentRequest] = Field(default_factory=list)


class CollectionSourceResultRequest(CamelCaseModel):
    source_id: int = Field(gt=0)
    status: CollectionSourceStatus
    error_message: str | None = Field(default=None, max_length=2000)
    documents: list[CollectionDocumentRequest] = Field(default_factory=list)

    @classmethod
    def from_collected(cls, result: SourceCollectionResult) -> "CollectionSourceResultRequest":
        return cls(
            source_id=result.source_id,
            status=(
                CollectionSourceStatus.COMPLETED
                if result.succeeded
                else CollectionSourceStatus.FAILED
            ),
            error_message=result.error_message,
            documents=[
                CollectionDocumentRequest(
                    original_url=document.original_url,
                    external_document_id=document.external_document_id,
                    title=document.title,
                    content_text=document.content_text,
                    published_at=(
                        document.published_at.isoformat()
                        if document.published_at is not None
                        else None
                    ),
                    attachments=[
                        CollectionAttachmentRequest(
                            file_name=attachment.file_name,
                            download_url=attachment.download_url,
                            content_type=attachment.content_type,
                            file_size=attachment.file_size,
                            file_hash=attachment.file_hash,
                            extracted_text=attachment.extracted_text,
                            parse_status=attachment.parse_status,
                            error_message=attachment.error_message,
                        )
                        for attachment in document.attachments
                    ],
                )
                for document in result.documents
            ],
        )


class CollectionResultRequest(CamelCaseModel):
    run_id: int = Field(gt=0)
    job_id: UUID
    sources: list[CollectionSourceResultRequest] = Field(min_length=1)

    @classmethod
    def from_collected(
        cls,
        run_id: int,
        job_id: UUID,
        results: list[SourceCollectionResult],
    ) -> "CollectionResultRequest":
        return cls(
            run_id=run_id,
            job_id=job_id,
            sources=[CollectionSourceResultRequest.from_collected(result) for result in results],
        )


class StoredDocumentResult(CamelCaseModel):
    original_url: str
    document_id: int
    version_id: int
    change_type: str
    analysis_required: bool


class CollectionResultData(CamelCaseModel):
    documents: list[StoredDocumentResult]


class CollectionResultResponse(CamelCaseModel):
    is_success: bool
    code: str
    http_status: int
    message: str
    data: CollectionResultData