import asyncio
import logging
import re
from uuid import UUID

import httpx

from app.core.config import SPRING_BOOT_BASE_URL, SPRING_BOOT_TIMEOUT_SECONDS
from app.domains.analysis.schemas.delivery import (
    AnalysisResultRequest,
    AnalysisResultResponse,
    ProposalResultRequest,
    ProposalResultResponse,
)

logger = logging.getLogger(__name__)


def _rejection_details(response: httpx.Response) -> str:
    # Only bounded field paths from the validation envelope, never rejected values/messages.
    fields = []
    if len(response.content) <= 16_384:
        try:
            body = response.json()
            data = body.get("data") if isinstance(body, dict) else None
            if isinstance(data, dict):
                fields = sorted(key for key in data if len(key) <= 160 and re.fullmatch(
                    r"(?:runId|jobId|results|failures|legalResults)"
                    r"(?:\[\d{1,5}\])?(?:\.[A-Za-z][A-Za-z0-9]{0,39}(?:\[\d{1,5}\])?){0,6}",
                    key,
                ))[:16]
        except ValueError:
            pass
    return f"status={response.status_code}" + (f" fields={','.join(fields)}" if fields else "")


class AnalysisResultClientError(RuntimeError):
    pass


class AnalysisResultClient:
    def __init__(
        self,
        base_url: str = SPRING_BOOT_BASE_URL,
        timeout_seconds: float = SPRING_BOOT_TIMEOUT_SECONDS,
        max_attempts: int = 3,
        retry_delay_seconds: float = 0.2,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout_seconds
        self._max_attempts = max_attempts
        self._retry_delay = retry_delay_seconds
        self._transport = transport

    async def send(self, request: AnalysisResultRequest) -> AnalysisResultResponse:
        async with httpx.AsyncClient(
            base_url=self._base_url,
            timeout=self._timeout,
            transport=self._transport,
        ) as client:
            for attempt in range(1, self._max_attempts + 1):
                try:
                    response = await client.post(
                        "/internal/monitoring/analysis-results",
                        json=request.model_dump(by_alias=True, mode="json"),
                    )
                    if 400 <= response.status_code < 500:
                        await self._notify_rejected(client, request.run_id, request.job_id,
                                                    "ANALYSIS", response.status_code)
                        raise AnalysisResultClientError(
                            f"분석 결과 요청이 거부되었습니다. {_rejection_details(response)}"
                        )
                    response.raise_for_status()
                    return AnalysisResultResponse.model_validate(response.json())
                except AnalysisResultClientError:
                    raise
                except (httpx.TransportError, httpx.HTTPStatusError) as exception:
                    if attempt == self._max_attempts:
                        raise AnalysisResultClientError(
                            "분석 결과 전달에 실패했습니다."
                        ) from exception
                    await asyncio.sleep(self._retry_delay)

        raise AnalysisResultClientError("분석 결과 전달에 실패했습니다.")

    async def send_proposals(
        self,
        request: ProposalResultRequest,
    ) -> ProposalResultResponse:
        async with httpx.AsyncClient(
            base_url=self._base_url,
            timeout=self._timeout,
            transport=self._transport,
        ) as client:
            for attempt in range(1, self._max_attempts + 1):
                try:
                    response = await client.post(
                        "/internal/monitoring/proposal-results",
                        json=request.model_dump(by_alias=True, mode="json"),
                    )
                    if 400 <= response.status_code < 500:
                        await self._notify_rejected(client, request.run_id, request.job_id,
                                                    "PROPOSAL", response.status_code)
                        raise AnalysisResultClientError(
                            f"사업 제안 결과 요청이 거부되었습니다. {_rejection_details(response)}"
                        )
                    response.raise_for_status()
                    return ProposalResultResponse.model_validate(response.json())
                except AnalysisResultClientError:
                    raise
                except (httpx.TransportError, httpx.HTTPStatusError) as exception:
                    if attempt == self._max_attempts:
                        raise AnalysisResultClientError(
                            "사업 제안 결과 전달에 실패했습니다."
                        ) from exception
                    await asyncio.sleep(self._retry_delay)

        raise AnalysisResultClientError("사업 제안 결과 전달에 실패했습니다.")

    async def _notify_rejected(
        self, client: httpx.AsyncClient, run_id: int, job_id: UUID, phase: str, status: int,
    ) -> None:
        if status == 429:
            return
        for attempt in range(1, self._max_attempts + 1):
            try:
                response = await client.post(
                    "/internal/monitoring/analysis-result-failures",
                    json={
                        "runId": run_id, "jobId": str(job_id), "phase": phase, "httpStatus": status,
                    },
                )
                response.raise_for_status()
                logger.info(
                    "결과 거절 통지 전달 완료. run_id=%s job_id=%s phase=%s", run_id, job_id, phase,
                )
                return
            except (httpx.TransportError, httpx.HTTPStatusError) as exception:
                rejected = isinstance(exception, httpx.HTTPStatusError) and (
                    400 <= exception.response.status_code < 500
                    and exception.response.status_code != 429
                )
                if rejected or attempt == self._max_attempts:
                    logger.warning(
                        "결과 거절 통지 실패. run_id=%s job_id=%s phase=%s type=%s",
                        run_id, job_id, phase, type(exception).__name__,
                    )
                    return  # Keep the original rejection; lease recovery remains available.
                await asyncio.sleep(self._retry_delay)
