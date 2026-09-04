from fastapi import APIRouter, BackgroundTasks, status

from app.domains.monitoring.schemas.request import MonitoringJobRequest
from app.domains.monitoring.schemas.response import MonitoringJobAcceptedResponse
from app.domains.monitoring.service import accept_monitoring_job
from app.domains.monitoring.tasks import run_monitoring_job

router = APIRouter(prefix="/internal/monitoring", tags=["internal-monitoring"])


@router.post(
    "/jobs",
    response_model=MonitoringJobAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
def create_monitoring_job(
    request: MonitoringJobRequest,
    background_tasks: BackgroundTasks,
) -> MonitoringJobAcceptedResponse:
    response = accept_monitoring_job()
    background_tasks.add_task(run_monitoring_job, response.job_id, request)
    return response
