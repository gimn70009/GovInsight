from uuid import uuid4

from app.domains.report.schemas.request import ReportJobRequest
from app.domains.report.schemas.response import (
    ReportJobAcceptedResponse,
    ReportJobStatus,
)


def accept_report_job(request: ReportJobRequest) -> ReportJobAcceptedResponse:
    return ReportJobAcceptedResponse(
        job_id=uuid4(),
        status=ReportJobStatus.ACCEPTED,
        document_count=len(request.documents),
    )
