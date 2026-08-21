from enum import StrEnum
from uuid import UUID

from pydantic import Field

from app.core.schemas import CamelCaseModel


class AnalysisJobStatus(StrEnum):
    ACCEPTED = "ACCEPTED"


class AnalysisJobAcceptedResponse(CamelCaseModel):
    job_id: UUID
    status: AnalysisJobStatus
    document_count: int = Field(gt=0)
