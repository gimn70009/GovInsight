from uuid import UUID, uuid4

from app.domains.analysis.schemas.response import (
    AnalysisJobAcceptedResponse,
    AnalysisJobStatus,
)


def accept_analysis_job(
    document_count: int, job_id: UUID | None = None
) -> AnalysisJobAcceptedResponse:
    return AnalysisJobAcceptedResponse(
        job_id=job_id or uuid4(),
        status=AnalysisJobStatus.ACCEPTED,
        document_count=document_count,
    )
