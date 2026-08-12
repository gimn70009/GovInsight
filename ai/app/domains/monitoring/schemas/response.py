from enum import StrEnum
from uuid import UUID

from app.core.schemas import CamelCaseModel


class MonitoringJobStatus(StrEnum):
    ACCEPTED = "ACCEPTED"


class MonitoringJobAcceptedResponse(CamelCaseModel):
    job_id: UUID
    status: MonitoringJobStatus
