from fastapi import APIRouter, BackgroundTasks, status

from app.domains.report.schemas.request import ReportJobRequest
from app.domains.report.schemas.response import ReportJobAcceptedResponse
from app.domains.report.service import accept_report_job
from app.domains.report.tasks import run_report_job

router = APIRouter(prefix="/internal/monitoring", tags=["internal-report"])


@router.post(
    "/report-jobs",
    response_model=ReportJobAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
def create_report_job(
    request: ReportJobRequest,
    background_tasks: BackgroundTasks,
) -> ReportJobAcceptedResponse:
    response = accept_report_job(request)
    background_tasks.add_task(run_report_job, response.job_id, request)
    return response
