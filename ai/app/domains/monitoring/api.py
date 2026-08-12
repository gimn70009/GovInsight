from fastapi import APIRouter, status

from app.domains.monitoring.schemas.request import MonitoringJobRequest
from app.domains.monitoring.schemas.response import MonitoringJobAcceptedResponse
from app.domains.monitoring.service import accept_monitoring_job

router = APIRouter(prefix="/internal/monitoring", tags=["internal-monitoring"])


@router.post(
    "/jobs",
    response_model=MonitoringJobAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
def create_monitoring_job(
    request: MonitoringJobRequest,
) -> MonitoringJobAcceptedResponse:
    return accept_monitoring_job(request)
