from fastapi import APIRouter

from app.domains.submission.schemas import NotesRequest, NotesResponse
from app.domains.submission.service import extract_notes

router = APIRouter(prefix="/internal/monitoring", tags=["internal-submission"])


@router.post("/submission-notes", response_model=NotesResponse)
async def submission_notes(request: NotesRequest) -> NotesResponse:
    return await extract_notes(request)
