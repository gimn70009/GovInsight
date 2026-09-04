from uuid import uuid4

from app.domains.analysis.schemas.response import (
    AnalysisJobAcceptedResponse,
    AnalysisJobStatus,
)


def accept_analysis_job(document_count: int) -> AnalysisJobAcceptedResponse:
    return AnalysisJobAcceptedResponse(
        job_id=uuid4(),
        status=AnalysisJobStatus.ACCEPTED,
        document_count=document_count,
    )
