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
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout_seconds
        self._transport = transport

    async def send(self, request: CollectionResultRequest) -> CollectionResultResponse:
        async with httpx.AsyncClient(
            base_url=self._base_url,
            timeout=self._timeout,
            transport=self._transport,
        ) as client:
            response = await client.post(
                "/internal/monitoring/collection-results",
                json=request.model_dump(by_alias=True, mode="json"),
            )
            response.raise_for_status()
            return CollectionResultResponse.model_validate(response.json())