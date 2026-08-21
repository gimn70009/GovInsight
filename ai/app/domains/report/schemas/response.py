from enum import StrEnum
from uuid import UUID

from pydantic import Field

from app.core.schemas import CamelCaseModel


class ReportJobStatus(StrEnum):
    ACCEPTED = "ACCEPTED"


class ReportJobAcceptedResponse(CamelCaseModel):
    job_id: UUID
    status: ReportJobStatus
    document_count: int = Field(gt=0)
