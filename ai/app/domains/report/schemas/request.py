from datetime import datetime
from uuid import UUID

from pydantic import Field

from app.core.schemas import CamelCaseModel
from app.domains.analysis.schemas.result import DocumentImportance, ProposalStrategy


class ReportAttachmentRequest(CamelCaseModel):
    file_name: str
    download_url: str
    extracted_text: str | None = None


class ReportComparisonRequest(CamelCaseModel):
    purpose: str | None = None
    application_deadline: str | None = None
    eligibility: str | None = None


class ReportDocumentRequest(CamelCaseModel):
    detection_id: int = Field(gt=0)
    document_id: int = Field(gt=0)
    version_id: int = Field(gt=0)
    organization_name: str = Field(min_length=1, max_length=100)
    board_name: str = Field(min_length=1, max_length=100)
    change_type: str = Field(min_length=1, max_length=30)
    title: str = Field(min_length=1, max_length=500)
    published_at: datetime | None = None
    original_url: str = Field(min_length=1, max_length=2000)
    summary: str = Field(min_length=1)
    key_points: list[str] = Field(default_factory=list, max_length=20)
    importance: DocumentImportance
    reason: str | None = Field(default=None, max_length=5000)
    eligibility: str | None = Field(default=None, max_length=30)
    opportunity_score: int | None = Field(default=None, ge=0, le=100)
    proposal: ProposalStrategy | None = None
    content_text: str | None = None
    comparison_summary: ReportComparisonRequest | None = None
    attachments: list[ReportAttachmentRequest] = Field(default_factory=list)


class ReportJobRequest(CamelCaseModel):
    job_id: UUID | None = None
    run_id: int = Field(gt=0)
    requested_at: datetime | None = None
    total_source_count: int = Field(ge=0)
    detected_document_count: int = Field(ge=0)
    warning_count: int = Field(ge=0)
    documents: list[ReportDocumentRequest] = Field(min_length=1)
