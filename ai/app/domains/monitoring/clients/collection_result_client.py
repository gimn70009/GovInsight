import asyncio

import httpx

from app.core.config import SPRING_BOOT_BASE_URL, SPRING_BOOT_TIMEOUT_SECONDS
from app.domains.monitoring.schemas.collection_result import (
    CollectionResultRequest,
    CollectionResultResponse,
)


class CollectionResultClient:
    def __init__(
        self,
        base_url: str = SPRING_BOOT_BASE_URL,
        timeout_seconds: float = SPRING_BOOT_TIMEOUT_SECONDS,
        transport: httpx.AsyncBaseTransport | None = None,
        max_attempts: int = 3,
        retry_delay_seconds: float = 0.2,
    ) -> None:
        if max_attempts < 1 or retry_delay_seconds < 0:
            raise ValueError("재시도 횟수는 1 이상, 대기 시간은 0 이상이어야 합니다.")
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout_seconds
        self._transport = transport
        self._max_attempts = max_attempts
        self._retry_delay = retry_delay_seconds

    async def send(self, request: CollectionResultRequest) -> CollectionResultResponse:
        async with httpx.AsyncClient(
            base_url=self._base_url,
            timeout=self._timeout,
            transport=self._transport,
        ) as client:
            payload = request.model_dump(by_alias=True, mode="json")
            for attempt in range(1, self._max_attempts + 1):
                try:
                    response = await client.post(
                        "/internal/monitoring/collection-results",
                        json=payload,
                    )
                    response.raise_for_status()
                    return CollectionResultResponse.model_validate(response.json())
                except (httpx.TransportError, httpx.HTTPStatusError) as exception:
                    if isinstance(exception, httpx.HTTPStatusError):
                        status = exception.response.status_code
                        if status != 429 and status < 500:
                            raise
                    if attempt == self._max_attempts:
                        raise
                    await asyncio.sleep(self._retry_delay * attempt)

        raise RuntimeError("수집 결과 전달에 실패했습니다.")