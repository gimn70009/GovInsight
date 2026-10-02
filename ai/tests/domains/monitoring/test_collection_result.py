import asyncio
from datetime import datetime
from uuid import UUID

import httpx
import pytest

from app.domains.monitoring.clients import CollectionResultClient
from app.domains.monitoring.schemas.collected_document import (
    CollectedAttachment,
    CollectedDocument,
    SourceCollectionResult,
)
from app.domains.monitoring.schemas.collection_result import CollectionResultRequest


def test_convert_collected_results_to_camel_case_request() -> None:
    request = CollectionResultRequest.from_collected(
        10,
        UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
        [
            SourceCollectionResult(
                source_id=1,
                documents=[
                    CollectedDocument(
                        original_url="https://example.com/notice/1",
                        external_document_id="1",
                        title="지원사업 공고",
                        content_text="본문",
                        published_at=datetime(2026, 8, 18, 9, 0),
                        attachments=[
                            CollectedAttachment(
                                file_name="공고.pdf",
                                download_url="https://example.com/file/1",
                                content_type="application/pdf",
                                file_size=1024,
                                file_hash="a" * 64,
                            )
                        ],
                    )
                ],
            )
        ],
    )

    body = request.model_dump(by_alias=True, mode="json")

    assert body["runId"] == 10
    assert body["sources"][0]["status"] == "COMPLETED"
    assert body["sources"][0]["documents"][0]["publishedAt"] == "2026-08-18T09:00:00"
    attachment = body["sources"][0]["documents"][0]["attachments"][0]
    assert attachment["fileName"] == "공고.pdf"
    assert attachment["contentType"] == "application/pdf"
    assert attachment["fileSize"] == 1024
    assert attachment["fileHash"] == "a" * 64
    assert attachment["parseStatus"] == "PENDING"


def test_send_collection_result_to_spring_boot() -> None:
    captured_body: dict[str, object] = {}

    def handle(request: httpx.Request) -> httpx.Response:
        captured_body.update(__import__("json").loads(request.content))
        return httpx.Response(
            200,
            json={
                "isSuccess": True,
                "code": "SUCCESS_200",
                "httpStatus": 200,
                "message": "요청에 성공했습니다.",
                "data": {
                    "documents": [
                        {
                            "originalUrl": "https://example.com/notice/1",
                            "documentId": 100,
                            "versionId": 200,
                            "changeType": "NEW_DOCUMENT",
                            "analysisRequired": True,
                        }
                    ]
                },
            },
        )

    request = CollectionResultRequest.from_collected(
        10,
        UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
        [SourceCollectionResult(source_id=1)],
    )
    client = CollectionResultClient(
        base_url="http://spring.test",
        transport=httpx.MockTransport(handle),
    )

    response = asyncio.run(client.send(request))

    assert captured_body["runId"] == 10
    assert response.data.documents[0].document_id == 100
    assert response.data.documents[0].analysis_required is True


@pytest.mark.parametrize("failure", ["disconnect", "lost_response", 500, 503, 429])
def test_retries_transient_failure_with_identical_payload(failure) -> None:
    bodies = []

    def handle(request: httpx.Request) -> httpx.Response:
        bodies.append(request.content)
        if len(bodies) == 1:
            if failure == "disconnect":
                raise httpx.ConnectError("offline", request=request)
            if failure == "lost_response":
                raise httpx.ReadTimeout("response lost", request=request)
            return httpx.Response(failure)
        return httpx.Response(200, json={
            "isSuccess": True, "code": "SUCCESS_200", "httpStatus": 200,
            "message": "OK", "data": {"documents": []},
        })

    request = CollectionResultRequest.from_collected(
        10, UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
        [SourceCollectionResult(source_id=1)],
    )
    client = CollectionResultClient(
        transport=httpx.MockTransport(handle), retry_delay_seconds=0,
    )
    response = asyncio.run(client.send(request))
    assert response.data.documents == []
    assert len(bodies) == 2
    assert bodies[0] == bodies[1]


@pytest.mark.parametrize("status, attempts", [(400, 1), (404, 1), (409, 1), (503, 3)])
def test_does_not_retry_permanent_errors_and_bounds_transient_retries(status, attempts) -> None:
    calls = []

    def handle(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(status)

    request = CollectionResultRequest.from_collected(
        10, UUID("3ed1132b-8d61-45d9-bfab-06c1ed96f202"),
        [SourceCollectionResult(source_id=1)],
    )
    client = CollectionResultClient(
        transport=httpx.MockTransport(handle), retry_delay_seconds=0,
    )
    with pytest.raises(httpx.HTTPStatusError):
        asyncio.run(client.send(request))
    assert len(calls) == attempts
