import asyncio
import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from .schemas import RewriteRequest, RewriteResponse
from .service import rewrite

router = APIRouter(prefix="/internal/monitoring", tags=["internal-participation"])


@router.post("/participation-rewrite", response_model=RewriteResponse)
async def rewrite_participation(request: RewriteRequest) -> RewriteResponse:
    return await rewrite(request)


@router.post("/participation-rewrite/stream")
async def stream_participation(request: RewriteRequest):
    async def events():
        queue = asyncio.Queue()

        async def progress(result):
            await queue.put((result, False))

        async def run():
            try:
                result = await rewrite(request, on_progress=progress)
            except Exception:
                result = RewriteResponse(
                    statements=[],
                    evidence_only_ids=[s.id for s in request.sources],
                    status="UNAVAILABLE",
                )
            await queue.put((result, True))

        task = asyncio.create_task(run())
        try:
            while True:
                result, done = await queue.get()
                # Each line is an independently validated snapshot, never streamed model tokens.
                yield (
                    json.dumps({"result": result.model_dump(), "done": done}, ensure_ascii=False)
                    + "\n"
                )
                if done:
                    break
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    return StreamingResponse(
        events(),
        media_type="application/x-ndjson",
        headers={"X-Accel-Buffering": "no", "Cache-Control": "no-store"},
    )
