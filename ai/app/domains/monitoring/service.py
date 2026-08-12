from uuid import uuid4

from app.domains.monitoring.schemas.request import MonitoringJobRequest
from app.domains.monitoring.schemas.response import (
    MonitoringJobAcceptedResponse,
    MonitoringJobStatus,
)


def accept_monitoring_job(_request: MonitoringJobRequest) -> MonitoringJobAcceptedResponse:
    return MonitoringJobAcceptedResponse(
        job_id=uuid4(),
        status=MonitoringJobStatus.ACCEPTED,
    )
