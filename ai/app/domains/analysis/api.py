from fastapi import APIRouter, BackgroundTasks, status

from app.domains.analysis.legal_pair import LegalPairRequest, LegalPairResponse, pair_reviewer
from app.domains.analysis.proposal_writer import (
    ProposalWriteRequest,
    ProposalWriteResponse,
    proposal_writer,
)
from app.domains.analysis.schemas.request import AnalysisJobRequest
from app.domains.analysis.schemas.response import AnalysisJobAcceptedResponse
from app.domains.analysis.service import accept_analysis_job
from app.domains.analysis.tasks import run_analysis_job

router = APIRouter(prefix="/internal/monitoring", tags=["internal-analysis"])


@router.post(
    "/analysis-jobs",
    response_model=AnalysisJobAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
def create_analysis_job(
    request: AnalysisJobRequest,
    background_tasks: BackgroundTasks,
) -> AnalysisJobAcceptedResponse:
    response = accept_analysis_job(len(request.documents))
    background_tasks.add_task(run_analysis_job, response.job_id, request)
    return response




@router.post("/legal-pair-review", response_model=LegalPairResponse)
async def compare_legal_pair(request: LegalPairRequest) -> LegalPairResponse:
    return await pair_reviewer.review(request)


@router.post("/proposal-write", response_model=ProposalWriteResponse)
async def write_proposal(request: ProposalWriteRequest) -> ProposalWriteResponse:
    return await proposal_writer.write(request)
