import asyncio

import httpx

from app.core.config import SPRING_BOOT_BASE_URL, SPRING_BOOT_TIMEOUT_SECONDS
from app.domains.report.schemas.delivery import (
    ReportResultRequest,
    ReportResultResponse,
)


class ReportResultClientError(RuntimeError):
    pass


class ReportResultClient:
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

    async def send(self, request: ReportResultRequest) -> ReportResultResponse:
        async with httpx.AsyncClient(
            base_url=self._base_url,
            timeout=self._timeout,
            transport=self._transport,
        ) as client:
            for attempt in range(1, self._max_attempts + 1):
                try:
                    response = await client.post(
                        "/internal/monitoring/report-results",
                        json=request.model_dump(by_alias=True, mode="json"),
                    )
                    if 400 <= response.status_code < 500:
                        raise ReportResultClientError(
                            f"보고서 결과 요청이 거부되었습니다. status={response.status_code}"
                        )
                    response.raise_for_status()
                    return ReportResultResponse.model_validate(response.json())
                except ReportResultClientError:
                    raise
                except (httpx.TransportError, httpx.HTTPStatusError) as exception:
                    if attempt == self._max_attempts:
                        raise ReportResultClientError(
                            "보고서 결과 전달에 실패했습니다."
                        ) from exception
                    await asyncio.sleep(self._retry_delay)

        raise ReportResultClientError("보고서 결과 전달에 실패했습니다.")
